package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.NameKey
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.service.RateBook
import org.aristonis.mywallet.domain.service.RoleCategories

/** Resources available to a recipe's preparation hook, only inside the save transaction. */
class EntrySaveContext(
    val old: Transaction?,
    val snapshot: FxSnapshot,
    val book: RateBook,
    val accounts: AccountRepository,
    val roleCategories: RoleCategories,
    private val debts: DebtRepository,
    private val debt: DebtRef?,
) {
    /**
     * The debt an owed-later deduction books into: what I owe the line's payee in the salary currency.
     *
     * A draft may carry the id its line had before an edit. That id is kept only while the debt is
     * still on the I-owe side in this currency and still names this payee; after a currency move or a
     * payee swap the line resolves its own debt, so the old payee is never charged for someone else.
     * The old debt stays guarded through the entry's old legs. The single entry debt reference never
     * applies here.
     */
    suspend fun deductionDebtId(line: DeductionLine): Long {
        val currencyCode = line.amount.currencyCode
        val kept = line.debtId?.let { debts.findById(it) }
        if (kept != null && kept.side == DebtSide.I_OWE && kept.currencyCode == currencyCode &&
            isSamePayee(kept, line)
        ) {
            return kept.id
        }
        return debts.getOrCreate(line.name, DebtSide.I_OWE, currencyCode).id
    }

    /**
     * The payee is the same when the debt's name still keys to the line's, or when the line's name is
     * what the stored entry had against that debt: renaming the debt (or the rule) later must not
     * split one obligation into two.
     */
    private fun isSamePayee(debt: Debt, line: DeductionLine): Boolean {
        val lineKey = NameKey.of(line.name)
        if (debt.key == lineKey) return true
        val stored = (old as? Transaction.Income)?.deductions.orEmpty()
        return stored.any { it.debtId == debt.id && NameKey.of(it.name) == lineKey }
    }

    /** Normalize an unused fee before choosing a category, so zero creates no fallback row. */
    suspend fun feeCategory(fee: Money?, picked: Long?): Long? =
        if (fee == null || fee.isZero) null
        else roleCategories.categoryFor(CategoryRole.FEES, picked)

    /** Resolve an optional name reference; the leg's side and currency remain authoritative. */
    suspend fun debtId(id: Long, side: DebtSide, currencyCode: String): Long {
        val reference = debt ?: return id
        val row = debts.getOrCreate(reference.counterparty, reference.side, reference.currencyCode)
        if (row.side != side) throw WalletException.DebtSideMismatch(row.id)
        if (row.currencyCode != currencyCode) {
            throw WalletException.CurrencyMismatch(currencyCode, row.currencyCode)
        }
        return row.id
    }
}
