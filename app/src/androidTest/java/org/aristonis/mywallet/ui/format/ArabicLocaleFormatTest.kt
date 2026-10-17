package org.aristonis.mywallet.ui.format

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.transaction.exampleRateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ArabicLocaleFormatTest {
    @Test
    fun arabicConfiguration_usesLatinDigitsForMoneyPluralAndRate() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val incoming = Configuration(base.resources.configuration).apply { setLocale(Locale("ar")) }
        val locale = AppLocale.from(incoming.locales[0])
        val context = base.createConfigurationContext(Configuration(incoming).apply { setLocale(locale) })
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            assertEquals("ar", context.resources.configuration.locales[0].language)
            assertEquals("latn", context.resources.configuration.locales[0].getUnicodeLocaleType("nu"))
            val formatted = MoneyFormatter { Locale.getDefault() }.format("1000".toBigDecimal(), 2, "USD")
            assertEquals("1,000.00 USD", formatted)
            assertEquals(Money.of("1000.50", "USD"), MoneyParser { Locale.getDefault() }.parseAmount("١٬٠٠٠٫٥٠", "USD"))
            val plural = context.resources.getQuantityString(R.plurals.category_transaction_count, 12, 12)
            assertFalse(plural.any { it in '٠'..'٩' || it in '۰'..'۹' })
            assertEquals("4.5", exampleRateText(locale))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
