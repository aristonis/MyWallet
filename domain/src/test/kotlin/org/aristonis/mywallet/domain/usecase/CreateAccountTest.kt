package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.FeeRule
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class CreateAccountTest {

    @Test
    fun storesEachDefaultFeeExactly() = runTest {
        for (mode in FeeMode.entries) {
            val accounts = FakeAccountRepository()
            val fee = FeeRule(mode, BigDecimal("2.125"))
            val id = CreateAccount(FakeCurrencyRepository(listOf(Currency("USD", "$", 2))), accounts)(
                name = "Wallet", typeKey = "card", currencyCode = "USD",
                openingBalance = Money.zero("USD"), defaultFee = fee,
            )
            assertEquals(fee, accounts.findById(id)!!.defaultFee)
        }
    }

    @Test
    fun createsAccount_andReturnsId() = runTest {
        val accounts = FakeAccountRepository()
        val createAccount = CreateAccount(
            currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2))),
            accounts = accounts,
        )

        val id = createAccount(name = "Cash", typeKey = "cash", currencyCode = "USD",
            openingBalance = Money.of("100", "USD"), defaultFee = null)

        assertEquals(1L, id)
        assertEquals("Cash", accounts.findById(1)?.name)
        assertEquals(Money.of("100", "USD"), accounts.findById(1)?.openingBalance)
    }

    @Test
    fun storesTheCreditLimit() = runTest {
        val accounts = FakeAccountRepository()
        val createAccount = CreateAccount(
            currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2))),
            accounts = accounts,
        )

        val id = createAccount(
            name = "Visa credit", typeKey = "credit_card", currencyCode = "USD",
            openingBalance = Money.of("0", "USD"), creditLimit = Money.of("2000", "USD"), defaultFee = null,
        )

        assertEquals(Money.of("2000", "USD"), accounts.findById(id)?.creditLimit)
    }

    @Test
    fun withoutALimitNoneIsStored() = runTest {
        val accounts = FakeAccountRepository()
        val id = CreateAccount(FakeCurrencyRepository(listOf(Currency("USD", "$", 2))), accounts)
            .invoke(name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("0",
                "USD"), defaultFee = null)

        assertEquals(null, accounts.findById(id)?.creditLimit)
    }

    @Test(expected = WalletException.CurrencyNotFound::class)
    fun unknownCurrency_throws() = runTest {
        CreateAccount(FakeCurrencyRepository(), FakeAccountRepository())
            .invoke(name = "Cash", typeKey = "cash", currencyCode = "XXX", openingBalance = Money.of("0",
                "XXX"), defaultFee = null)
    }
}
