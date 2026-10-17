package org.aristonis.mywallet.data.format

import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class MoneyParserLiveLocaleTest {
    @Test
    fun localeProvider_isReadForEachParseAndPrefill() {
        var locale = Locale.US
        val parser = MoneyParser { locale }
        assertEquals(Money.of("1.25", "EUR"), parser.parseAmount("1.25", "EUR"))
        locale = Locale.GERMANY
        assertEquals(Money.of("1.25", "EUR"), parser.parseAmount("1,25", "EUR"))
        assertEquals("1,25", parser.toInputString(BigDecimal("1.25")))
    }
}
