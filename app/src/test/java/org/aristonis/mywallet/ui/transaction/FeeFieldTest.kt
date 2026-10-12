package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.FeeRule
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

class FeeFieldTest {
    private val form = EntryFeeForm(MoneyParser(Locale.US), MoneyFormatter(Locale.US),
        org.aristonis.mywallet.di.ErrorReporter { _, failure -> throw AssertionError(failure) })
    private val fx = FxSnapshot("USD", emptyMap(), listOf(Currency("USD", "$", 2)))
    private val date = LocalDate.of(2026, 10, 12)
    private fun input(amount: String = "300") = RateQuoteInput(fx, listOf(QuoteLeg("USD", amount, false)),
        emptyMap(), pair = QuotePair("USD", "USD"))

    @Test fun `percentage fee leaves exactly the remainder for the destination`() {
        val state = form.recalculated(FeeFormState(FeeMode.PERCENT, "2"), TransactionType.TRANSFER,
            "300", "USD", "USD", input(), null)
        assertEquals(Money.of("6", "USD"), state.amount)
        assertEquals(Money.of("294", "USD"), state.accountMovement)
    }

    @Test fun `zero removes a fee rather than storing a zero fee`() {
        val entry = Transaction.Transfer(sourceAccountId = 1, destAccountId = 2, sourceAmount = Money.of("300", "USD"),
            destAmount = Money.of("300", "USD"), rateUsed = BigDecimal.ONE, date = date)
        val state = form.recalculated(FeeFormState(FeeMode.PERCENT, "0"), TransactionType.TRANSFER,
            "300", "USD", "USD", input(), null)
        assertNull(form.forSave(entry, state).let { it as Transaction.Transfer }.fee)
    }

    @Test fun `expense fee is optional and adds on top of typed principal`() {
        val blank = form.recalculated(FeeFormState(), TransactionType.EXPENSE,
            "50", "USD", null, input("50"), null)
        assertNull(blank.amount)
        val state = form.recalculated(FeeFormState(FeeMode.FIXED, "1"), TransactionType.EXPENSE,
            "50", "USD", null, input("50"), null)
        assertEquals(Money.of("51", "USD"), state.accountMovement)
    }

    @Test fun `fee rounding follows domain currency precision once`() {
        val state = form.recalculated(FeeFormState(FeeMode.PERCENT, "2"), TransactionType.TRANSFER,
            "333.33", "USD", "USD", input("333.33"), null)
        assertEquals(Money.of("6.67", "USD"), state.amount)
        assertEquals(Money.of("326.66", "USD"), state.accountMovement)
    }

    @Test fun `autofill retains exact account default digits`() {
        val state = form.fromDefault(FeeRule(FeeMode.PERCENT, BigDecimal("2.125")))
        assertEquals(FeeMode.PERCENT, state.mode)
        assertEquals("2.125", state.valueInput)
    }

    @Test fun `clearing fee or changing its mode removes stale digits`() {
        val state = FeeFormState(FeeMode.FIXED, "9")
        assertEquals("", state.withMode(FeeMode.PERCENT).valueInput)
        assertEquals("9", state.withMode(FeeMode.FIXED).valueInput)
    }
    @Test fun `negative fee has the typed fee refusal`() {
        val state = form.recalculated(FeeFormState(FeeMode.FIXED, "-1"), TransactionType.EXPENSE,
            "50", "USD", null, input("50"), null)
        assertEquals(org.aristonis.mywallet.ui.message.UiMessage.FeeValueNegative, state.error)
    }

    @Test fun `fee consuming the whole transfer is refused before save`() {
        val state = form.recalculated(FeeFormState(FeeMode.PERCENT, "100"), TransactionType.TRANSFER,
            "300", "USD", "USD", input(), null)
        assertEquals(org.aristonis.mywallet.ui.message.UiMessage.FeeNotBelowAmount, state.error)
    }

    @Test fun `expense may have a fee larger than its principal`() {
        val state = form.recalculated(FeeFormState(FeeMode.FIXED, "100"), TransactionType.EXPENSE,
            "50", "USD", null, input("50"), null)
        assertNull(state.error)
        assertEquals(Money.of("150", "USD"), state.accountMovement)
    }

    @Test fun `cross currency fee stays in source and only the remainder converts`() {
        val foreign = fx.copy(ratesToBase = mapOf("EUR" to BigDecimal("1.2")),
            currencies = fx.currencies + Currency("EUR", "€", 2))
        val rates = input("333.33").copy(fx = foreign, pair = QuotePair("USD", "EUR"))
        val state = form.recalculated(FeeFormState(FeeMode.PERCENT, "2"), TransactionType.TRANSFER,
            "333.33", "USD", "EUR", rates, null)
        assertEquals(Money.of("6.67", "USD"), state.amount)
        assertEquals(Money.of("272.22", "EUR"), state.accountMovement)
    }

    @Test fun `missing conversion rate is a refusal rather than a gross receipt`() {
        val state = form.recalculated(FeeFormState(FeeMode.PERCENT, "2"), TransactionType.TRANSFER,
            "300", "USD", "EUR", input().copy(pair = QuotePair("USD", "EUR")), null)
        assertEquals(org.aristonis.mywallet.ui.message.UiMessage.MissingRate("EUR"), state.error)
        assertNull(state.accountMovement)
    }

    @Test fun `invalid typed rate refuses preview instead of using saved rate`() {
        val rates = input().copy(pair = QuotePair("USD", "EUR"), rateInputs = mapOf("EUR" to "wrong"))
        val state = form.recalculated(FeeFormState(FeeMode.PERCENT, "2"), TransactionType.TRANSFER,
            "300", "USD", "EUR", rates, null)
        assertEquals(org.aristonis.mywallet.ui.message.UiMessage.NotANumber, state.error)
        assertNull(state.accountMovement)
    }

    @Test fun `comma decimal fee input is parsed as exact locale money`() {
        val localized = EntryFeeForm(MoneyParser(Locale.GERMANY), MoneyFormatter(Locale.GERMANY),
            org.aristonis.mywallet.di.ErrorReporter { _, failure -> throw AssertionError(failure) })
        val state = localized.recalculated(FeeFormState(FeeMode.FIXED, "1,25"), TransactionType.EXPENSE,
            "50", "USD", null, input("50"), null)
        assertEquals(Money.of("1.25", "USD"), state.amount)
        assertEquals(Money.of("51.25", "USD"), state.accountMovement)
    }

    @Test fun `edited transfer receipt uses its locked pair instead of current rates`() {
        val original = Transaction.Transfer(1, 1, 2, Money.of("300", "USD"), Money.of("588", "EUR"),
            BigDecimal("2"), date, fee = Money.of("6", "USD"), feeCategoryId = 9)
        val foreign = fx.copy(ratesToBase = mapOf("EUR" to BigDecimal("1.2")),
            currencies = fx.currencies + Currency("EUR", "€", 2))
        val state = form.recalculated(FeeFormState(FeeMode.FIXED, "6"), TransactionType.TRANSFER,
            "400", "USD", "EUR", input("400").copy(fx = foreign), original)
        assertEquals(Money.of("788", "EUR"), state.accountMovement)
    }

    @Test fun `cleared visible transfer rate leaves no receipt despite a saved rate`() {
        val foreign = fx.copy(ratesToBase = mapOf("EUR" to BigDecimal("1.2")),
            currencies = fx.currencies + Currency("EUR", "€", 2))
        val rates = input().copy(fx = foreign, pair = QuotePair("USD", "EUR"), rateInputs = mapOf("EUR" to ""))
        val state = form.recalculated(FeeFormState(FeeMode.PERCENT, "2"), TransactionType.TRANSFER,
            "300", "USD", "EUR", rates, null)
        assertEquals(org.aristonis.mywallet.ui.message.UiMessage.MissingRate("EUR"), state.error)
        assertNull(state.accountMovement)
    }

    @Test fun `hidden typed rates cannot block a locked transfer fee preview`() {
        val original = Transaction.Transfer(1, 1, 2, Money.of("300", "USD"), Money.of("588", "EUR"),
            BigDecimal("2"), date, fee = Money.of("6", "USD"), feeCategoryId = 9)
        val state = form.recalculated(FeeFormState(FeeMode.FIXED, "6"), TransactionType.TRANSFER,
            "400", "USD", "EUR", input("400").copy(legs = emptyList(), pair = null,
                rateInputs = mapOf("EUR" to "wrong")), original)
        assertNull(state.error)
        assertEquals(Money.of("788", "EUR"), state.accountMovement)
    }

    @Test fun `hidden typed rates cannot block a same currency transfer fee preview`() {
        val foreign = FxSnapshot("EUR", mapOf("USD" to BigDecimal("1.2")), fx.currencies)
        val rates = input().copy(fx = foreign, rateInputs = mapOf("USD" to "wrong"))
        val state = form.recalculated(FeeFormState(FeeMode.PERCENT, "2"), TransactionType.TRANSFER,
            "300", "USD", "USD", rates, null)
        assertNull(state.error)
        assertEquals(Money.of("294", "USD"), state.accountMovement)
    }

    @Test fun `large cross currency receipt uses the same rounded pair as save`() {
        val foreign = fx.copy(ratesToBase = mapOf("EUR" to BigDecimal("3")),
            currencies = fx.currencies + Currency("EUR", "€", 2))
        val rates = input("30000000001").copy(fx = foreign, pair = QuotePair("USD", "EUR"))
        val state = form.recalculated(FeeFormState(FeeMode.FIXED, "1"), TransactionType.TRANSFER,
            "30000000001", "USD", "EUR", rates, null)
        assertEquals(Money.of("9999999999.99", "EUR"), state.accountMovement)
    }

    @Test fun `a removed category is cleared with a visible fallback notice`() {
        val state = FeeFormState(valueInput = "1", categoryId = 4).withCategories(emptyList())
        assertNull(state.categoryId)
        org.junit.Assert.assertTrue(state.categoryRemoved)
    }

    @Test fun `a fixed fee clears on currency change but a percentage remains`() {
        assertEquals("", FeeFormState(FeeMode.FIXED, "5").onCurrencyChanged("USD", "EUR").valueInput)
        assertEquals("5", FeeFormState(FeeMode.PERCENT, "5").onCurrencyChanged("USD", "EUR").valueInput)
    }

    private val withLira = FxSnapshot("USD", mapOf("SYP" to BigDecimal("0.0001")),
        listOf(Currency("USD", "$", 2), Currency("SYP", "SYP", 0)))

    private fun fixedFee(value: String, code: String, amount: String = "300"): FeeFormState = form.recalculated(
        FeeFormState(FeeMode.FIXED, value), TransactionType.TRANSFER, amount, code, code,
        RateQuoteInput(withLira, emptyList(), emptyMap(), pair = QuotePair(code, code)), null)

    @Test fun `a fixed fee with more decimals than its currency is refused, not rounded`() {
        val refused = fixedFee("5.555", "USD")
        assertEquals(UiMessage.FeeTooManyDecimals("USD", 2), refused.error)
        assertNull(refused.amount)
        assertEquals(Money.of("5.55", "USD"), fixedFee("5.55", "USD").amount)
        assertEquals(Money.of("5.55", "USD"), fixedFee("5.550", "USD").amount)
    }

    @Test fun `a currency without decimals takes only a whole fixed fee`() {
        assertEquals(UiMessage.FeeTooManyDecimals("SYP", 0), fixedFee("5.5", "SYP", "1000").error)
        assertEquals(Money.of("5", "SYP"), fixedFee("5", "SYP", "1000").amount)
        assertEquals(Money.of("995", "SYP"), fixedFee("5.0", "SYP", "1000").accountMovement)
    }

    @Test fun `a percentage fee may have more decimals than the currency`() {
        val state = form.recalculated(FeeFormState(FeeMode.PERCENT, "2.125"), TransactionType.TRANSFER,
            "300", "USD", "USD", input(), null)
        assertNull(state.error)
        assertEquals(Money.of("6.38", "USD"), state.amount)
    }
}
