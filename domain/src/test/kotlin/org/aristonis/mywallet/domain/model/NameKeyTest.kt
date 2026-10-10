package org.aristonis.mywallet.domain.model

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Pins how a typed name becomes the key that decides "same person": trimmed and case-folded with a
 * fixed locale, so the same debt is found on every device, and a blank name is refused.
 */
class NameKeyTest {

    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun paddedAndDifferentlyCasedNamesGiveOneKey() {
        assertEquals(NameKey.of(" ali "), NameKey.of("Ali"))
        assertEquals("ali", NameKey.of(" ALI ").value)
    }

    @Test
    fun differentNamesGiveDifferentKeys() {
        assertEquals(false, NameKey.of("Ali") == NameKey.of("Alia"))
    }

    @Test(expected = WalletException.NameBlank::class)
    fun emptyNameThrowsNameBlank() {
        NameKey.of("")
    }

    @Test(expected = WalletException.NameBlank::class)
    fun whitespaceOnlyNameThrowsNameBlank() {
        NameKey.of("   \t ")
    }

    @Test
    fun foldingUsesLocaleRootNotTheDeviceLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))

        assertEquals(NameKey.of("istanbul"), NameKey.of("ISTANBUL"))
        assertEquals("istanbul", NameKey.of("ISTANBUL").value)
    }
}
