package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

/**
 * One place that answers "what is this currency worth in the base?" for a form, a save and a
 * repayment: the base is always 1, a rate typed on the form wins over the saved one, and a currency
 * with neither has no rate rather than a guessed one.
 */
class RateBookTest {

    private val fx = FxSnapshot(
        baseCurrencyCode = "SYP",
        ratesToBase = mapOf("USD" to BigDecimal("13000"), "EUR" to BigDecimal("14100"), "SYP" to BigDecimal("2")),
        currencies = listOf(Currency("SYP", "£S", 0), Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("GBP", "£", 2)),
    )

    @Test
    fun theBaseIsAlwaysOneEvenIfARateWasSavedOrTypedForIt() {
        assertEquals(BigDecimal.ONE, RateBook(fx, overrides = mapOf("SYP" to BigDecimal("5"))).rateToBase("SYP"))
    }

    @Test
    fun aTypedRateWinsOverTheSavedOne() {
        assertEquals(BigDecimal("13250"), RateBook(fx, overrides = mapOf("USD" to BigDecimal("13250"))).rateToBase("USD"))
    }

    @Test
    fun theSavedRateIsUsedWhenNothingWasTyped() {
        assertEquals(BigDecimal("14100"), RateBook(fx).rateToBase("EUR"))
    }

    @Test
    fun aCurrencyWithNeitherHasNoRate() {
        assertNull(RateBook(fx).rateToBase("GBP"))
        assertNull(RateBook(fx).pairRate("GBP", "USD"))
    }

    @Test
    fun aPairRateGoesThroughTheBaseExactlyAsATransferStoresIt() {
        val expected = BigDecimal("13000").divide(BigDecimal("14100"), CurrencyConverter.RATE_SCALE, CurrencyConverter.ROUNDING)
        assertEquals(expected, RateBook(fx).pairRate("USD", "EUR"))
        assertEquals(BigDecimal("13000").setScale(CurrencyConverter.RATE_SCALE), RateBook(fx).pairRate("USD", "SYP"))
    }

    @Test
    fun stampingRatesOnlyTheCategoryLegsAndKeepsARateAlreadyThere() {
        val legs = listOf(
            Leg(LegRole.ACCOUNT, 0, LegTarget.Account(1), Money.of("-5", "USD")),
            Leg(LegRole.MAIN, 0, LegTarget.Category(3, null), Money.of("5", "USD")),
            Leg(LegRole.FEE, 0, LegTarget.Category(4, null), Money.of("1", "EUR"), rateToBase = BigDecimal("14000")),
            Leg(LegRole.DEBT, 0, LegTarget.Debt(9, DebtSide.I_OWE), Money.of("5", "USD")),
            Leg(LegRole.DEDUCTION, 0, LegTarget.Category(5, null), Money.of("2", "GBP")),
        )

        val stamped = RateBook(fx).stamp(legs)

        assertEquals(listOf(null, BigDecimal("13000"), BigDecimal("14000"), null, null), stamped.map { it.rateToBase })
    }

    @Test
    fun aTypedRateIsStampedWithoutChangingTheLegsOrTheSavedRates() {
        val before = fx.copy()
        val legs = listOf(Leg(LegRole.MAIN, 0, LegTarget.Category(3, null), Money.of("5", "USD")))

        val stamped = RateBook(fx, overrides = mapOf("USD" to BigDecimal("13250"))).stamp(legs)

        assertEquals(BigDecimal("13250"), stamped.single().rateToBase)
        assertNull(legs.single().rateToBase)
        assertEquals(before, fx)
        assertEquals(BigDecimal("13000"), RateBook(fx).rateToBase("USD"))
    }

    @Test
    fun aCategoryLegInTheBaseIsStampedOneWhateverWasTyped() {
        val stamped = RateBook(fx, overrides = mapOf("SYP" to BigDecimal("5")))
            .stamp(listOf(Leg(LegRole.MAIN, 0, LegTarget.Category(3, null), Money.of("500", "SYP"))))
        assertEquals(BigDecimal.ONE, stamped.single().rateToBase)
    }

    @Test
    fun aTypedRateForTheBaseChangesNoConversionOrPairRate() {
        val book = RateBook(fx, overrides = mapOf("SYP" to BigDecimal("5")))
        assertEquals(Money.of("26000", "SYP"), book.converter().convert(Money.of("2", "USD"), "SYP"))
        assertEquals(RateBook(fx).pairRate("USD", "SYP"), book.pairRate("USD", "SYP"))
    }

    @Test
    fun onEditALegKeepsItsOldRateOnlyIfItsCurrencyIsTheSameAndNothingWasTyped() {
        val oldUsd = Leg(LegRole.MAIN, 0, LegTarget.Category(3, null), Money.of("5", "USD"), BigDecimal("12900"))
        val sameCurrency = oldUsd.copy(amount = Money.of("6", "USD"))
        assertEquals(BigDecimal("12900"), RateBook(fx).restamp(listOf(sameCurrency), old = listOf(oldUsd)).single().rateToBase)
    }

    @Test
    fun onEditALegMovedToAnotherCurrencyIsRateAgain() {
        val oldUsd = Leg(LegRole.MAIN, 0, LegTarget.Category(3, null), Money.of("5", "USD"), BigDecimal("13000"))
        // The entry still carries the USD rate after its account was changed to SYP.
        val nowSyp = oldUsd.copy(amount = Money.of("65000", "SYP"))
        assertEquals(BigDecimal.ONE, RateBook(fx).restamp(listOf(nowSyp), old = listOf(oldUsd)).single().rateToBase)
    }

    @Test
    fun onEditATypedRateWinsOverTheOldOne() {
        val oldUsd = Leg(LegRole.MAIN, 0, LegTarget.Category(3, null), Money.of("5", "USD"), BigDecimal("12900"))
        val book = RateBook(fx, overrides = mapOf("USD" to BigDecimal("13250")))
        assertEquals(BigDecimal("13250"), book.restamp(listOf(oldUsd), old = listOf(oldUsd)).single().rateToBase)
    }

    @Test
    fun onEditANewLegIsRatedAndNonCategoryLegsAreLeftAlone() {
        val account = Leg(LegRole.ACCOUNT, 0, LegTarget.Account(1), Money.of("-5", "USD"))
        val fee = Leg(LegRole.FEE, 0, LegTarget.Category(4, null), Money.of("1", "EUR"))
        val restamped = RateBook(fx).restamp(listOf(account, fee), old = listOf(account))
        assertEquals(listOf(null, BigDecimal("14100")), restamped.map { it.rateToBase })
    }

    @Test
    fun anAddedCategoryRoleReusesTheOldRateForItsCurrency() {
        val oldMain = Leg(LegRole.MAIN, 0, LegTarget.Category(3, null), Money.of("100", "USD"), BigDecimal("12900"))
        val addedFee = Leg(LegRole.FEE, 0, LegTarget.Category(4, null), Money.of("2", "USD"))

        assertEquals(BigDecimal("12900"), RateBook(fx).restamp(listOf(addedFee), listOf(oldMain)).single().rateToBase)
    }

    @Test
    fun editingPreservesDistinctRatesForExistingCategoryLegPositions() {
        val old = listOf(
            Leg(LegRole.MAIN, 0, LegTarget.Category(3, null), Money.of("100", "USD"), BigDecimal("12900")),
            Leg(LegRole.FEE, 0, LegTarget.Category(4, null), Money.of("2", "USD"), BigDecimal("13100")),
        )

        val updated = RateBook(fx).restamp(old.map { it.copy(amount = it.amount + Money.of("1", "USD")) }, old)

        assertEquals(listOf(BigDecimal("12900"), BigDecimal("13100")), updated.map { it.rateToBase })
    }

    @Test
    fun aNewRoleInACurrencyWithTwoOldRatesTakesTheMainLegRateWhateverTheOrder() {
        val oldFee = Leg(LegRole.FEE, 0, LegTarget.Category(4, null), Money.of("2", "USD"), BigDecimal("13100"))
        val oldMain = Leg(LegRole.MAIN, 0, LegTarget.Category(3, null), Money.of("100", "USD"), BigDecimal("12900"))
        val addedDeduction = Leg(LegRole.DEDUCTION, 0, LegTarget.Category(5, null), Money.of("10", "USD"))

        val restamped = RateBook(fx).restamp(listOf(addedDeduction), old = listOf(oldFee, oldMain))

        assertEquals(BigDecimal("12900"), restamped.single().rateToBase)
    }

    @Test
    fun aNewRoleInACurrencyWithTwoOldRatesAndNoMainLegTakesTheFirstOldRate() {
        val oldFee = Leg(LegRole.FEE, 0, LegTarget.Category(4, null), Money.of("2", "USD"), BigDecimal("13100"))
        val oldInterest = Leg(LegRole.INTEREST_PAID, 0, LegTarget.Category(7, null), Money.of("3", "USD"), BigDecimal("12800"))
        val addedDeduction = Leg(LegRole.DEDUCTION, 0, LegTarget.Category(5, null), Money.of("10", "USD"))

        val restamped = RateBook(fx).restamp(listOf(addedDeduction), old = listOf(oldFee, oldInterest))

        assertEquals(BigDecimal("13100"), restamped.single().rateToBase)
    }

    @Test
    fun aTypedRateOfZeroOrLessIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { RateBook(fx, overrides = mapOf("USD" to BigDecimal.ZERO)) }
        assertThrows(IllegalArgumentException::class.java) { RateBook(fx, overrides = mapOf("USD" to BigDecimal("-1"))) }
    }

    @Test
    fun theConverterUsesTypedRatesToo() {
        val converted = RateBook(fx, overrides = mapOf("USD" to BigDecimal("13250"))).converter().convert(Money.of("2", "USD"), "SYP")
        assertEquals(Money.of("26500", "SYP"), converted)
    }
}
