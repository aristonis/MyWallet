package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class ExplicitLegQuoteTest {
    private val fx = FxSnapshot("SYP", mapOf("USD" to BigDecimal("13000"), "EUR" to BigDecimal("14000")),
        listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("SYP", "£S", 2)))
    private val quoter = RateQuoter(MoneyParser(Locale.US), MoneyFormatter(Locale.US))

    @Test fun nonTrackingLegsProduceNoRateFieldsOrBaseEquivalent() {
        val quote = quoter.quote(RateQuoteInput(fx, listOf(QuoteLeg("USD", "50", false)), emptyMap()))
        assertTrue(quote.rateFields.isEmpty())
        assertNull(quote.baseEquivalent)
        assertFalse(quote.rateRequired)
    }

    @Test fun trackingLegsUseTheirOwnCurrencyAndHistoricalRate() {
        val quote = quoter.quote(RateQuoteInput(fx, listOf(QuoteLeg("EUR", "50", true)), emptyMap(),
            keptRates = mapOf("EUR" to BigDecimal("11000"))))
        assertEquals("EUR", quote.rateFields.single().currencyCode)
        assertEquals("11000", quote.rateFields.single().input)
        assertNotNull(quote.baseEquivalent)
    }

    @Test fun multipleTrackingLegsExposeEachDistinctCurrencyOnce() {
        val quote = quoter.quote(RateQuoteInput(fx,
            listOf(QuoteLeg("EUR", "50", true), QuoteLeg("USD", "20", true), QuoteLeg("EUR", "5", true)), emptyMap()))
        assertEquals(listOf("EUR", "USD"), quote.rateFields.map { it.currencyCode })
    }

    @Test fun anExplicitTransferPairRetainsTheExistingConversionPreview() {
        val quote = quoter.quote(RateQuoteInput(fx, listOf(QuoteLeg("USD", "14", false)), emptyMap(),
            pair = QuotePair("USD", "EUR")))
        assertEquals(listOf("USD", "EUR"), quote.rateFields.map { it.currencyCode })
        assertNotNull(quote.pairRate)
        assertNotNull(quote.receivedDisplay)
        assertFalse(quote.rateRequired)
    }
}
