package org.aristonis.mywallet.domain.usecase.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.port.CategoryLeg
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.LegRate
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import java.math.BigDecimal

/**
 * The account legs of whatever a [FakeTransactionRepository] holds, so tests of the old
 * transaction use cases can still read balances through `GetAccountBalances(accounts, ledger)`.
 * Only [observeAccountLegs] is backed; the other reads are not needed there. Goes away with the old
 * transaction use cases.
 */
fun FakeTransactionRepository.asLedger(): LedgerQueries = TransactionLedger(this)

private class TransactionLedger(private val transactions: FakeTransactionRepository) : LedgerQueries {

    override fun observeAccountLegs(): Flow<List<AccountLeg>> = transactions.observeAll().map { all ->
        all.flatMap { tx ->
            tx.toParts().legs.mapNotNull { leg -> (leg.target as? LegTarget.Account)?.let { AccountLeg(it.id, leg.amount) } }
        }
    }

    override fun observeCategoryLegs(range: DateRange): Flow<List<CategoryLeg>> = unsupported()
    override fun observeUnknownKind(known: Set<String>): Flow<UnreadableRowRef?> = unsupported()
    override suspend fun isAccountInUse(accountId: Long): Boolean = unsupported()
    override fun observeCategoryUsage(): Flow<Map<Long, Int>> = unsupported()
    override suspend fun fillMissingRate(code: String, rate: BigDecimal): Int = unsupported()
    override suspend fun distinctLegRates(): List<LegRate> = unsupported()

    private fun unsupported(): Nothing = error("not backed by TransactionLedger")
}
