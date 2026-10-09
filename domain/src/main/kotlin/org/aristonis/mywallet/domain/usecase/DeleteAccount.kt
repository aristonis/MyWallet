package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.LedgerQueries

/**
 * Hard-deletes an account, but only when nothing references it. Deleting an account that still has
 * legs would orphan that history and quietly drop its balance from net worth, so it fails loud with
 * [WalletException.AccountInUse] and the UI steers the user to archive. An account no leg is on is
 * safe to remove outright.
 *
 * "In use" is one indexed question to the ledger, which stops at the first leg it finds; no entry
 * list is read, so the cost does not grow with the history.
 */
class DeleteAccount(
    private val accounts: AccountRepository,
    private val ledger: LedgerQueries,
) {
    suspend operator fun invoke(id: Long) {
        accounts.findById(id) ?: throw WalletException.AccountNotFound(id)
        if (ledger.isAccountInUse(id)) throw WalletException.AccountInUse(id)
        accounts.delete(id)
    }
}
