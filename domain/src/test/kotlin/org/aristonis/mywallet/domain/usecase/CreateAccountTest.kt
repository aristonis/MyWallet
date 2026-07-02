package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class CreateAccountTest {

    @Test
    fun createsAccount_andReturnsId() = runTest {
        val accounts = FakeAccountRepository()
        val createAccount = CreateAccount(
            currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2))),
            accounts = accounts,
        )

        val id = createAccount(name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD"))

        assertEquals(1L, id)
        assertEquals("Cash", accounts.findById(1)?.name)
        assertEquals(Money.of("100", "USD"), accounts.findById(1)?.openingBalance)
    }

    @Test(expected = WalletException.CurrencyNotFound::class)
    fun unknownCurrency_throws() = runTest {
        CreateAccount(FakeCurrencyRepository(), FakeAccountRepository())
            .invoke(name = "Cash", typeKey = "cash", currencyCode = "XXX", openingBalance = Money.of("0", "XXX"))
    }
}
