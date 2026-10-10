package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.TransactionRunner

/** Renames an existing debt inside one transaction, retaining its identity and recorded history. */
class RenameDebt(private val runner: TransactionRunner, private val debts: DebtRepository) {
    suspend operator fun invoke(id: Long, counterparty: String) {
        runner.inTransaction {
            debts.findById(id) ?: throw WalletException.DebtNotFound(id)
            debts.rename(id, counterparty)
        }
    }
}
