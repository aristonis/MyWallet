package org.aristonis.mywallet.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLocaleTest {
    @Test
    fun arabicLocale_keepsLanguageAndRegionWithLatinDigits() {
        val locale = AppLocale.from(Locale.forLanguageTag("ar-SY"))
        assertEquals("ar", locale.language)
        assertEquals("SY", locale.country)
        assertEquals("latn", locale.getUnicodeLocaleType("nu"))
    }

    @Test
    fun englishLocale_keepsLanguageAndRegion() {
        assertEquals("en-US-u-nu-latn", AppLocale.from(Locale.US).toLanguageTag())
    }

    @Test
    fun incomingLocale_winsOverPreviouslyOverriddenDefault() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-u-nu-latn"))
            assertEquals("de-DE-u-nu-latn", AppLocale.from(Locale.GERMANY).toLanguageTag())
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun existingExtensions_surviveNumberingOverride() {
        val locale = AppLocale.from(Locale.forLanguageTag("ar-EG-u-ca-islamic-nu-arab"))
        assertEquals("islamic", locale.getUnicodeLocaleType("ca"))
        assertEquals("latn", locale.getUnicodeLocaleType("nu"))
    }
    @Test
    fun currentLocale_readsEachDefaultChangeAndUsesLatinDigits() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SY"))
            assertEquals("ar-SY-u-nu-latn", AppLocale.current().toLanguageTag())
            Locale.setDefault(Locale.GERMANY)
            assertEquals("de-DE-u-nu-latn", AppLocale.current().toLanguageTag())
        } finally {
            Locale.setDefault(previous)
        }
    }
}
