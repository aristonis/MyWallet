package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class SetBaseCurrencyTest {

    @Test
    fun savesSettingsWithChosenBaseCurrency() = runTest {
        val settings = FakeSettingsRepository()
        val setBaseCurrency = SetBaseCurrency(
            currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2))),
            settings = settings,
        )

        setBaseCurrency("USD")

        assertEquals("USD", settings.get().baseCurrencyCode)
    }

    @Test(expected = WalletException.CurrencyNotFound::class)
    fun unknownCurrency_throws() = runTest {
        SetBaseCurrency(FakeCurrencyRepository(), FakeSettingsRepository()).invoke("XXX")
    }
}
