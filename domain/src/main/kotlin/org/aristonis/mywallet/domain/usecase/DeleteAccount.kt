package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.involvesAccount
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.TransactionRepository

/**
 * Hard-deletes an account, but only when nothing references it. Transactions have no foreign key to
 * accounts, so deleting an account that still has transactions would orphan that history and quietly
 * drop its balance from net worth — instead we fail loud with [WalletException.AccountInUse] and the
 * UI steers the user to archive. An account with no transactions is safe to remove outright.
 */
class DeleteAccount(
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
) {
    suspend operator fun invoke(id: Long) {
        accounts.findById(id) ?: throw WalletException.AccountNotFound(id)
        val inUse = transactions.observeAll().first().any { it.involvesAccount(id) }
        if (inUse) throw WalletException.AccountInUse(id)
        accounts.delete(id)
    }
}
