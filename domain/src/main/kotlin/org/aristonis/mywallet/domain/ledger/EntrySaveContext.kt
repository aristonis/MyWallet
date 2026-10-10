package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
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
