package org.aristonis.mywallet.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.data.db.CategoryLegRow
import org.aristonis.mywallet.data.db.LedgerQueryDao
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.port.CategoryLeg
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.LegRate
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import java.math.BigDecimal

/**
 * The ledger's read-only projections over Room. The DAO filters and projects; amounts and rates
 * are parsed here from their exact text, and every sum happens later in the domain with BigDecimal.
 */
class RoomLedgerQueries(private val dao: LedgerQueryDao) : LedgerQueries {

    override fun observeAccountLegs(): Flow<List<AccountLeg>> = dao.observeAccountLegs().map { rows ->
        rows.map { AccountLeg(it.accountId, Money.of(BigDecimal(it.amount), it.currencyCode)) }
    }

    override fun observeCategoryLegs(range: DateRange): Flow<List<CategoryLeg>> =
        dao.observeCategoryLegs(range.start, range.endInclusive).map { rows -> rows.map { it.toCategoryLeg() } }

    override fun observeUnknownKind(known: Set<String>): Flow<UnreadableRowRef?> =
        dao.observeFirstUnknownKind(known.toList()).map { row -> row?.let { UnreadableRowRef(it.id, it.kind) } }

    override suspend fun isAccountInUse(accountId: Long): Boolean = dao.isAccountInUse(accountId)

    override fun observeCategoryUsage(): Flow<Map<Long, Int>> =
        dao.observeCategoryUsage().map { rows -> rows.associate { it.categoryId to it.entryCount } }

    override suspend fun fillMissingRate(code: String, rate: BigDecimal): Int =
        dao.fillMissingRate(code, rate.toPlainString())

    override suspend fun distinctLegRates(): List<LegRate> =
        dao.distinctLegRates().map { LegRate(it.currencyCode, it.rateToBase?.let(::BigDecimal)) }
}

/**
 * A stored leg as Tracking sees it. A leg on no target, a role this build does not know or a value
 * that does not parse is bad stored data, reported as the entry it belongs to. A category row that is
 * gone, or of a kind this build does not know, is passed on as a null kind for Tracking to report.
 */
private fun CategoryLegRow.toCategoryLeg(): CategoryLeg = try {
    CategoryLeg(
        entryId = entryId,
        date = date,
        categoryId = requireNotNull(categoryId) { "leg of entry $entryId is on no target" },
        subCategoryId = subCategoryId,
        entryKind = entryKind,
        categoryKind = CategoryKind.entries.firstOrNull { it.name == categoryKind },
        role = requireNotNull(LegRole.entries.firstOrNull { it.name == role }) { "unknown leg role $role" },
        amount = Money.of(BigDecimal(amount), currencyCode),
        rateToBase = rateToBase?.let(::BigDecimal),
    )
} catch (e: IllegalArgumentException) {
    // Covers NumberFormatException from the decimal parses as well as the checks above.
    throw WalletException.UnreadableRow(entryId, entryKind, e)
}
