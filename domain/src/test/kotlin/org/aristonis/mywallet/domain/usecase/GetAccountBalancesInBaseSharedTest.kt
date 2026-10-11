package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountBalance
import org.aristonis.mywallet.domain.model.CreditStatus
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.usecase.fake.FakeFxRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class GetAccountBalancesInBaseSharedTest {
    @Test fun convertsTheProvidedBalancesAndKeepsTheCreditStatusOnTheSameRow() = runTest {
        val account = Account(1, "Card", "credit_card", "EUR", Money.zero("EUR"), creditLimit = Money.of("100", "EUR"))
        val credit = CreditStatus.of(Money.of("-120", "EUR"), account.creditLimit)
        val balances = MutableStateFlow(listOf(AccountBalance(account, Money.of("-120", "EUR"), credit)))
        val fx = FakeFxRepository(FxSnapshot("USD", mapOf("EUR" to BigDecimal("1.25")),
            listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))))
        val converted = GetAccountBalancesInBase(fx)(balances).first().single()
        assertEquals(Money.of("-120", "EUR"), converted.native)
        assertEquals(Money.of("-150", "USD"), converted.base)
        assertEquals(credit, converted.credit)
    }

    @Test fun missingRateLeavesNativeCreditStatusAccessible() = runTest {
        val account = Account(1, "Card", "credit_card", "EUR", Money.zero("EUR"), creditLimit = Money.of("100", "EUR"))
        val credit = CreditStatus.of(Money.of("-20", "EUR"), account.creditLimit)
        val balances = MutableStateFlow(listOf(AccountBalance(account, Money.of("-20", "EUR"), credit)))
        val fx = FakeFxRepository(FxSnapshot("USD", emptyMap(), listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))))
        val converted = GetAccountBalancesInBase(fx)(balances).first().single()
        assertNull(converted.base)
        assertEquals(credit, converted.credit)
    }
}
