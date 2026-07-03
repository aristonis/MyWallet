package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.port.AccountRepository

/**
 * Archives or unarchives an account. Archived accounts drop out of the active accounts list and net
 * worth but keep their history — the safe alternative to deleting an account that has transactions.
 */
class SetAccountArchived(
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(id: Long, archived: Boolean) {
        val account = accounts.findById(id) ?: throw WalletException.AccountNotFound(id)
        accounts.upsert(account.copy(archived = archived))
    }
}
