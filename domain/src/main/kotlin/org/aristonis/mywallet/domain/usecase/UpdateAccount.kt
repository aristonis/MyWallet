package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.involvesAccount
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.TransactionRepository

/**
 * Edits an existing account's name / type / currency in place (its id is preserved). The currency may
 * only change while the account has NO transactions: once money is recorded against it, switching the
 * currency would silently re-denominate that history, so we fail loud with
 * [WalletException.AccountCurrencyLocked]. A currency change is otherwise validated against the
 * currency list. Because [Account.copy][org.aristonis.mywallet.domain.model.Account] carries the
 * untouched fields forward, `archived` and `sortOrder` survive the edit; name/type non-blank and the
 * opening-balance-currency match are enforced by the Account model's invariants.
 */
class UpdateAccount(
    private val currencies: CurrencyRepository,
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
) {
    suspend operator fun invoke(
        id: Long,
        name: String,
        typeKey: String,
        currencyCode: String,
        openingBalance: Money,
    ) {
        val existing = accounts.findById(id) ?: throw WalletException.AccountNotFound(id)
        if (currencyCode != existing.currencyCode) {
            val hasTransactions = transactions.observeAll().first().any { it.involvesAccount(id) }
            if (hasTransactions) throw WalletException.AccountCurrencyLocked(id)
            currencies.findByCode(currencyCode) ?: throw WalletException.CurrencyNotFound(currencyCode)
        }
        accounts.upsert(
            existing.copy(
                name = name,
                typeKey = typeKey,
                currencyCode = currencyCode,
                openingBalance = openingBalance,
            ),
        )
    }
}
