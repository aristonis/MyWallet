package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import java.time.LocalDate

/** Changes or clears details after checking existence inside the same transaction as the write. */
class UpdateDebtDetails(private val runner: TransactionRunner, private val debts: DebtRepository) {
    suspend operator fun invoke(id: Long, dueDate: LocalDate?, note: String?) {
        runner.inTransaction {
            debts.findById(id) ?: throw WalletException.DebtNotFound(id)
            debts.updateDetails(id, dueDate, note)
        }
    }
}
