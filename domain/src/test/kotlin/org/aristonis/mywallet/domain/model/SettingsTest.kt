package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {

    @Test
    fun defaults() {
        val s = Settings(baseCurrencyCode = "USD")
        assertEquals(ThemePreference.SYSTEM, s.theme)
        assertEquals(1, s.schemaVersion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankBaseCurrency_throws() {
        Settings(baseCurrencyCode = "")
    }
}
