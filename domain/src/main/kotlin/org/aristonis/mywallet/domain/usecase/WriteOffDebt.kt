package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.DebtEntryGuard
import org.aristonis.mywallet.domain.service.DebtGuard
import java.math.BigDecimal
import java.time.LocalDate

/** Settles the exact remaining debt without account movement, recording the loss or forgiveness. */
class WriteOffDebt(
    private val runner: TransactionRunner,
    debts: DebtRepository,
    ledger: LedgerQueries,
    private val saveEntry: SaveEntry,
) {
    private val debtGuard = DebtEntryGuard(debts, ledger)

    /** The chosen category is required; SaveEntry checks its kind and stamps its historical rate. */
    suspend operator fun invoke(
        debtId: Long,
        categoryId: Long,
        date: LocalDate,
        typedRates: Map<String, BigDecimal> = emptyMap(),
        subCategoryId: Long? = null,
        note: String? = null,
    ): SavedEntry = runner.inTransaction {
        require(categoryId > 0) { "a write-off requires a chosen category" }
        val debt = debtGuard.requireDebt(debtId)
        val remaining = debtGuard.remaining(setOf(debtId))[debtId]?.remaining ?: Money.zero(debt.currencyCode)
        DebtGuard.check(debt, remaining)
        if (remaining.isZero) throw WalletException.DebtHasNoRemaining(debt.counterparty)
        saveEntry(
            Transaction.WriteOff(
                side = debt.side, debtId = debt.id, amount = remaining, categoryId = categoryId,
                subCategoryId = subCategoryId, date = date, note = note,
            ),
            typedRates,
        )
    }
}
