package org.aristonis.mywallet.ui.reports.charts

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.port.CategoryLeg
import org.aristonis.mywallet.domain.port.DatedLegs
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.LegRate
import org.aristonis.mywallet.domain.port.NetWorthLegReader
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The ledger as the two chart screens read it: category legs for a window (Spending breakdown),
 * account legs and debt legs (the balances under Net worth), and the ledger's health. Entries are
 * projected the way the real ledger does, through each kind's recipe.
 */
internal class ChartLedger(initial: List<Transaction> = emptyList()) : LedgerQueries {

    /** Every stored entry; setting it is a write that every read sees. */
    val entries = MutableStateFlow(initial)

    val debtLegs = MutableStateFlow<List<DebtLeg>>(emptyList())

    /** The first stored entry of a kind no recipe reads; null while the ledger is healthy. */
    val unknownKind = MutableStateFlow<UnreadableRowRef?>(null)

    /** The next this-many category reads fail, as a read over an undecodable row would. */
    var failedCategoryReadsLeft = 0

    /** Once failures are used up, category reads never answer: a retry still reading. */
    var categoryReadsHang = false

    override fun observeCategoryLegs(range: DateRange): Flow<List<CategoryLeg>> =
        flow {
            if (failedCategoryReadsLeft > 0) {
                failedCategoryReadsLeft--
                throw IllegalStateException("a stored row could not be read")
            }
            if (categoryReadsHang) awaitCancellation()
            emitAll(entries)
        }.map { all -> all.filter { it.date in range }.flatMap(::categoryLegsOf) }

    override fun observeAccountLegs(): Flow<List<AccountLeg>> = entries.map { all ->
        all.flatMap { entry ->
            entry.toParts().legs.mapNotNull { leg -> (leg.target as? LegTarget.Account)?.let { AccountLeg(it.id, leg.amount) } }
        }
    }

    override fun observeDebtLegs(): Flow<List<DebtLeg>> = debtLegs
    override fun observeUnknownKind(known: Set<String>): Flow<UnreadableRowRef?> = unknownKind
    override suspend fun debtLegs(debtIds: Set<Long>): List<DebtLeg> = debtLegs.value.filter { it.debtId in debtIds }

    // The chart screens read nothing else; failing keeps a drifting test from passing quietly.
    override suspend fun isAccountInUse(accountId: Long): Boolean = error("charts do not check account use")
    override fun observeAccountsInUse(): Flow<Set<Long>> = error("charts do not check account use")
    override fun observeCategoryUsage(): Flow<Map<Long, Int>> = error("charts do not read category usage")
    override suspend fun fillMissingRate(code: String, rate: BigDecimal): Int = error("charts do not write rates")
    override suspend fun distinctLegRates(): List<LegRate> = error("charts do not read leg rates")

    private fun categoryLegsOf(tx: Transaction): List<CategoryLeg> {
        val parts = tx.toParts()
        return parts.legs.mapNotNull { leg ->
            val target = leg.target as? LegTarget.Category ?: return@mapNotNull null
            CategoryLeg(
                entryId = tx.id, date = tx.date, categoryId = target.id, subCategoryId = target.subId,
                entryKind = parts.header.kind, role = leg.role, amount = leg.amount, rateToBase = leg.rateToBase,
            )
        }
    }
}

/**
 * Dated account and debt legs set directly by a test, so it can move them separately from the
 * balances, as the four Room reads do. Only legs after the asked day come back, as in the real read.
 */
internal class FakeNetWorthLegReader : NetWorthLegReader {

    val legs = MutableStateFlow(DatedLegs(account = emptyList(), debt = emptyList(), overLimit = false))

    /** The day each read was opened after, in order. */
    val readsAfter = mutableListOf<LocalDate>()

    /** The next this-many reads fail. */
    var failedReadsLeft = 0

    override fun observeLegsAfter(after: LocalDate, limit: Int): Flow<DatedLegs> = flow {
        readsAfter += after
        if (failedReadsLeft > 0) {
            failedReadsLeft--
            throw IllegalStateException("a stored row could not be read")
        }
        emitAll(legs)
    }.map { all ->
        all.copy(account = all.account.filter { it.date.isAfter(after) }, debt = all.debt.filter { it.date.isAfter(after) })
    }
}
