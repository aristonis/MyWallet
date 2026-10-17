package org.aristonis.mywallet.data.format

import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class MoneyFormatterLiveLocaleTest {
    @Test
    fun localeProvider_tracksChangesWithoutRecreatingFormatter() {
        var locale = Locale.US
        val formatter = MoneyFormatter { locale }
        assertEquals("1,000.50 EUR", formatter.format("1000.5".toBigDecimal(), 2, "EUR"))
        locale = Locale.GERMANY
        assertEquals("1.000,50 EUR", formatter.format("1000.5".toBigDecimal(), 2, "EUR"))
    }
}
