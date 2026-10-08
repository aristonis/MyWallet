package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetSaveTransactionRatesTest {

    @Test
    fun savingRatesFromTransactionsIsOnByDefault() {
        assertTrue(Settings(baseCurrencyCode = "SYP").saveTransactionRates)
    }

    @Test
    fun switchingItOffKeepsEverythingElse() = runTest {
        val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "SYP", theme = ThemePreference.DARK))

        SetSaveTransactionRates(settings).invoke(false)

        val saved = settings.get()
        assertFalse(saved.saveTransactionRates)
        assertEquals("SYP", saved.baseCurrencyCode)
        assertEquals(ThemePreference.DARK, saved.theme)
    }

    @Test(expected = IllegalStateException::class)
    fun missingSettingsFailsLoud() = runTest {
        SetSaveTransactionRates(FakeSettingsRepository()).invoke(true)
    }
}
