package org.aristonis.mywallet.ui.transaction

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class RateExampleTest {
    @Test
    fun arabicRateExample_usesLatinDigitsWithEitherNumberingExtension() {
        listOf("ar", "ar-u-nu-latn").forEach { languageTag ->
            assertEquals("4.5", exampleRateText(Locale.forLanguageTag(languageTag)))
        }
    }

    @Test
    fun rateExample_readsTheSuppliedLocaleOnEveryCall() {
        assertEquals("4.5", exampleRateText(Locale.US))
        assertEquals("4,5", exampleRateText(Locale.GERMANY))
        assertEquals("4.5", exampleRateText(Locale.forLanguageTag("ar")))
    }
}
