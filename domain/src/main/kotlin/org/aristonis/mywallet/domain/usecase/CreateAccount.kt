package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository

/** Creates an account (used by onboarding and the accounts screen). Returns the new account id. */
class CreateAccount(
    private val currencies: CurrencyRepository,
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(
        name: String,
        typeKey: String,
        currencyCode: String,
        openingBalance: Money,
    ): Long {
        currencies.findByCode(currencyCode)
            ?: throw WalletException.CurrencyNotFound(currencyCode)
        return accounts.upsert(
            Account(name = name, typeKey = typeKey, currencyCode = currencyCode, openingBalance = openingBalance),
        )
    }
}
