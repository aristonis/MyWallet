package org.aristonis.mywallet.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.data.db.AccountLegRow
import org.aristonis.mywallet.data.db.CategoryLegRow
import org.aristonis.mywallet.data.db.DebtLegRow
import org.aristonis.mywallet.data.db.LedgerQueryDao
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.port.CategoryLeg
import org.aristonis.mywallet.domain.port.DebtLeg
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
        rows.map { row ->
            try {
                AccountLeg(row.accountId, Money.of(BigDecimal(row.amount), row.currencyCode))
            } catch (e: IllegalArgumentException) {
                // Covers NumberFormatException, e.g. "12,50" or a legacy "--5".
                throw unreadableAccountLeg(row, e)
            }
        }
    }

    /**
     * Names the entry behind an account leg that does not parse. The balance projection carries no
     * entry id, to stay inside its covering index, so the id is looked up here, only on this rare path.
     */
    private suspend fun unreadableAccountLeg(row: AccountLegRow, cause: IllegalArgumentException): Exception {
        val owner = dao.findAccountLegOwner(row.accountId, row.amount)
            // The leg went away between the two reads. The read still fails; Retry reads the table again.
            ?: return IllegalStateException("an account leg on account ${row.accountId} could not be read", cause)
        return WalletException.UnreadableRow(owner.id, owner.kind, cause)
    }

    override fun observeDebtLegs(): Flow<List<DebtLeg>> = dao.observeDebtLegs().map { rows ->
        rows.map { row ->
            try {
                val side = requireNotNull(DEBT_SIDE_BY_NAME[row.side]) { "unknown debt side ${row.side}" }
                DebtLeg(row.debtId, side, Money.of(BigDecimal(row.amount), row.currencyCode))
            } catch (e: IllegalArgumentException) {
                // Covers NumberFormatException as well as the side check above.
                throw unreadableDebtLeg(row, e)
            }
        }
    }

    /** Names the entry behind a debt leg that cannot be read, looked up only on this rare path. */
    private suspend fun unreadableDebtLeg(row: DebtLegRow, cause: IllegalArgumentException): Exception {
        val owner = dao.findDebtLegOwner(row.debtId, row.amount)
            ?: return IllegalStateException("a debt leg on debt ${row.debtId} could not be read", cause)
        return WalletException.UnreadableRow(owner.id, owner.kind, cause)
    }

    /** All time takes the legs-led read; any narrower range starts from the date index. */
    override fun observeCategoryLegs(range: DateRange): Flow<List<CategoryLeg>> {
        val rows = if (range == DateRange.ALL_TIME) {
            dao.observeAllCategoryLegs()
        } else {
            dao.observeCategoryLegs(range.start, range.endInclusive)
        }
        return rows.map { list -> list.map { it.toCategoryLeg() } }
    }

    override fun observeUnknownKind(known: Set<String>): Flow<UnreadableRowRef?> =
        dao.observeFirstUnknownKind(known.toList()).map { row -> row?.let { UnreadableRowRef(it.id, it.kind) } }

    override suspend fun isAccountInUse(accountId: Long): Boolean = dao.isAccountInUse(accountId)

    override fun observeAccountsInUse(): Flow<Set<Long>> =
        dao.observeAccountsInUse().map { it.toSet() }.distinctUntilChanged()

    override fun observeCategoryUsage(): Flow<Map<Long, Int>> =
        dao.observeCategoryUsage().map { rows -> rows.associate { it.categoryId to it.entryCount } }

    override suspend fun fillMissingRate(code: String, rate: BigDecimal): Int =
        dao.fillMissingRate(code, rate.toPlainString())

    override suspend fun distinctLegRates(): List<LegRate> =
        dao.distinctLegRates().map { LegRate(it.currencyCode, it.rateToBase?.let(::BigDecimal)) }
}

private val DEBT_SIDE_BY_NAME: Map<String, DebtSide> = DebtSide.entries.associateBy { it.name }

private val LEG_ROLE_BY_NAME: Map<String, LegRole> = LegRole.entries.associateBy { it.name }

/**
 * A stored leg as Tracking sees it. A leg on no target, a role this build does not know or a value
 * that does not parse is bad stored data, reported as the entry it belongs to. The category row is
 * not read: a leg on a category that is gone still counts, by its entry's kind, under its id.
 */
private fun CategoryLegRow.toCategoryLeg(): CategoryLeg = try {
    CategoryLeg(
        entryId = entryId,
        date = date,
        categoryId = requireNotNull(categoryId) { "leg of entry $entryId is on no target" },
        subCategoryId = subCategoryId,
        entryKind = entryKind,
        role = requireNotNull(LEG_ROLE_BY_NAME[role]) { "unknown leg role $role" },
        amount = Money.of(BigDecimal(amount), currencyCode),
        rateToBase = rateToBase?.let(::BigDecimal),
    )
} catch (e: IllegalArgumentException) {
    // Covers NumberFormatException from the decimal parses as well as the checks above.
    throw WalletException.UnreadableRow(entryId, entryKind, e)
}
