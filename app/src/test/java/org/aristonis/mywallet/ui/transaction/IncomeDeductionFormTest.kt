package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.RefusedInputException
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

class IncomeDeductionFormTest {
    private val form = IncomeDeductionForm(MoneyParser(Locale.US))
    private val fx = FxSnapshot("USD", mapOf("EUR" to BigDecimal("2")),
        listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)))
    private fun item(id: Long, value: String, timing: DeductionTiming,
                     mode: DeductionMode = DeductionMode.PERCENT, code: String? = null) =
        DeductionItem(id, "item$id", mode, BigDecimal(value), code, timing, 9, setOf(1))
    private fun calculate(rows: List<IncomeDeductionDraft>, amount: String = "1000", currency: String = "USD",
                          rates: Map<String, String> = emptyMap()) =
        form.calculate(rows, amount, currency, RateQuoteInput(fx, emptyList(), rates))

    @Test fun `salary summary separates all expenses from paid now and owed later`() {
        val rows = form.defaults(listOf(item(1, "10", DeductionTiming.LATER),
            item(2, "5", DeductionTiming.LATER), item(3, "20", DeductionTiming.NOW,
                DeductionMode.FIXED, "USD")), 1)
        val result = calculate(rows)
        assertEquals(Money.of("1000", "USD"), result.summary!!.gross)
        assertEquals(Money.of("170", "USD"), result.summary!!.deductions)
        assertEquals(Money.of("980", "USD"), result.summary!!.accountReceives)
        assertEquals(Money.of("150", "USD"), result.summary!!.owed)
    }
    @Test fun `unticked draft stays unticked when recomputed`() {
        val rows = form.defaults(listOf(item(1, "10", DeductionTiming.LATER)), 1)
            .map { it.copy(selected = false) }
        assertEquals(emptyList<DeductionLine>(), calculate(rows, "2000").lines)
        assertEquals(false, rows.single().selected)
    }
    @Test fun `all paid now permits zero receipt`() {
        val result = calculate(form.defaults(listOf(item(1, "100", DeductionTiming.NOW)), 1))
        assertEquals(Money.zero("USD"), result.summary!!.accountReceives)
        assertNull(result.error)
    }
    @Test fun `foreign fixed converted override is entry only`() {
        val rows = form.defaults(listOf(item(1, "20", DeductionTiming.NOW, DeductionMode.FIXED, "EUR")), 1)
        assertEquals(Money.of("40", "USD"), calculate(rows).lines.single().amount)
        assertEquals(Money.of("35", "USD"), calculate(rows.map { it.copy(overrideInput = "35") }).lines.single().amount)
        assertEquals("20", rows.single().valueInput)
    }
    @Test fun `missing foreign rate blocks calculation`() {
        val rows = form.defaults(listOf(item(1, "20", DeductionTiming.NOW, DeductionMode.FIXED, "GBP")), 1)
        assertNotNull(calculate(rows).error)
        assertNull(calculate(rows).summary)
        assertEquals(Money.of("60", "USD"), calculate(rows, rates = mapOf("GBP" to "3")).lines.single().amount)
    }
    @Test fun `fixed historical amount survives gross and saved rate changes`() {
        val old = DeductionLine(1, 0, "old", DeductionMode.FIXED, BigDecimal("20"), "EUR",
            DeductionTiming.LATER, 9, Money.of("31", "USD"), 7)
        val rows = form.snapshots(listOf(old))
        assertEquals(old, calculate(rows).lines.single())
        assertEquals(old, calculate(rows, "2000").lines.single())
        assertEquals(Money.of("80", "USD"), calculate(rows, rates = mapOf("EUR" to "4")).lines.single().amount)
    }
    @Test fun `percentage snapshot reprices gross while preserving identity`() {
        val old = DeductionLine(1, 0, "old", DeductionMode.PERCENT, BigDecimal("10"), null,
            DeductionTiming.LATER, 9, Money.of("100", "USD"), 7)
        val line = calculate(form.snapshots(listOf(old)), "2000").lines.single()
        assertEquals(Money.of("200", "USD"), line.amount)
        assertEquals(7L, line.debtId)
        assertEquals("old", line.name)
    }
    @Test fun `zero rounded snapshot survives no op`() {
        val old = DeductionLine(1, 0, "tiny", DeductionMode.PERCENT, BigDecimal("1"), null,
            DeductionTiming.LATER, 9, Money.zero("USD"))
        assertEquals(old, calculate(form.snapshots(listOf(old)), "0.01").lines.single())
    }
    @Test fun `switching currency does not carry historical override`() {
        val old = DeductionLine(1, 0, "old", DeductionMode.FIXED, BigDecimal("20"), "EUR",
            DeductionTiming.NOW, 9, Money.of("31", "USD"))
        assertEquals(Money.of("20", "EUR"), calculate(form.snapshots(listOf(old)), currency = "EUR").lines.single().amount)
    }
    @Test fun `disabled items are never proposed on new incomes`() {
        val rows = form.defaults(listOf(item(1, "10", DeductionTiming.NOW).copy(enabled = false)), 1)
        assertEquals(emptyList<IncomeDeductionDraft>(), rows)
    }
    @Test fun `known converted amount does not require a foreign source rate`() {
        val rows = form.defaults(listOf(item(1, "20", DeductionTiming.NOW, DeductionMode.FIXED, "GBP")), 1)
            .map { it.copy(overrideInput = "35") }
        val result = calculate(rows)
        assertNull(result.error)
        assertEquals(Money.of("35", "USD"), result.lines.single().amount)
    }
    @Test fun `historical fixed amount stays valid when source rate is missing`() {
        val old = DeductionLine(1, 0, "old", DeductionMode.FIXED, BigDecimal("20"), "GBP",
            DeductionTiming.NOW, 9, Money.of("31", "USD"))
        val result = calculate(form.snapshots(listOf(old)))
        assertNull(result.error)
        assertEquals(old, result.lines.single())
    }

    @Test fun `save refuses deductions that no longer price instead of storing none`() {
        val rows = form.edit(form.defaults(listOf(item(1, "10", DeductionTiming.NOW)), 1), 1,
            DeductionEdit.SetValue("120"), "USD")
        val income = Transaction.Income(accountId = 1, amount = Money.of("1000", "USD"), categoryId = 1,
            date = LocalDate.of(2026, 10, 12))
        val refusal = assertThrows(RefusedInputException::class.java) {
            form.forSave(income, IncomeDeductionFormState(drafts = rows), "1000", RateQuoteInput(fx, emptyList(), emptyMap()))
        }
        assertEquals(UiMessage.DeductionOutOfRange, refusal.uiMessage)
    }

    @Test fun `a cleared converted amount stays empty and the computed amount is used`() {
        val rows = form.defaults(listOf(item(1, "20", DeductionTiming.NOW, DeductionMode.FIXED, "EUR")), 1)
        val typed = form.edit(rows, 1, DeductionEdit.SetConverted("35"), "USD")
        val cleared = form.edit(typed, 1, DeductionEdit.SetConverted(""), "USD")
        val state = recalculate(cleared)

        assertEquals("", state.convertedInputs.getValue(1))
        assertEquals(Money.of("40", "USD"), state.lines.single().amount)
    }

    @Test fun `a new value shows the computed converted amount again`() {
        val rows = form.defaults(listOf(item(1, "20", DeductionTiming.NOW, DeductionMode.FIXED, "EUR")), 1)
        val cleared = form.edit(rows, 1, DeductionEdit.SetConverted(""), "USD")
        val state = recalculate(form.edit(cleared, 1, DeductionEdit.SetValue("21"), "USD"))

        assertEquals(MoneyParser(Locale.US).toInputString(state.lines.single().amount.amount),
            state.convertedInputs.getValue(1))
        assertEquals(Money.of("42", "USD"), state.lines.single().amount)
    }

    private fun recalculate(rows: List<IncomeDeductionDraft>) = form.recalculated(
        IncomeDeductionFormState(drafts = rows), true, "1000", "USD", RateQuoteInput(fx, emptyList(), emptyMap()))
}
