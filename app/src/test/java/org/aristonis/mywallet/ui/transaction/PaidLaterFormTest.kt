package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.*
import org.aristonis.mywallet.ui.message.RefusedInputException
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class PaidLaterFormTest {
    private val form = PaidLaterForm(MoneyParser(Locale.US))
    private fun usd(value: String) = Money.of(value, "USD")
    private fun priced(x: String, amount: String = "1000") = form.recalculated(
        PaidLaterFormState(enabled = true, paidNowInput = x, counterparty = "Shop A"), amount, "USD")
    private fun expense() = Transaction.Expense(accountId = 1, amount = usd("1000"),
        categoryId = 6, date = LocalDate.of(2026, 10, 17))

    @Test fun owedUpdatesFromTypedPaidNowWhileKeepingPaidNowStable() {
        assertEquals(usd("700"), priced("300").owed)
        val increased = form.recalculated(priced("300"), "1200", "USD")
        assertEquals("300", increased.paidNowInput)
        assertEquals(usd("900"), increased.owed)
    }
    @Test fun emptyPaidNowMeansZeroAndDisablesFees() {
        val state = priced("")
        assertEquals(usd("0"), state.paidNow)
        assertEquals(usd("1000"), state.owed)
        assertFalse(state.feeEnabled)
    }
    @Test fun paidNowAtOrAboveAmountNamesTheRefusal() {
        listOf("1000", "1001").forEach { x ->
            assertEquals(UiMessage.PaidNowNotBelowAmount, priced(x).error)
            assertFalse(priced(x).allowsSave)
        }
    }
    @Test fun negativeAndMalformedPaidNowCannotSave() {
        listOf("-1", "abc").forEach { assertNotNull(priced(it).error); assertFalse(priced(it).allowsSave) }
    }
    @Test fun blankNameBlocksSaveAndDirectSubmission() {
        val state = priced("300").copy(counterparty = " ")
        assertFalse(state.allowsSave)
        assertThrows(RefusedInputException::class.java) { form.forSave(expense(), state) }
    }
    @Test fun typedNewNameBuildsDebtReferenceAndAttachesOwed() {
        val built = form.forSave(expense(), priced("300"))
        assertEquals(DebtRef("Shop A", DebtSide.I_OWE, "USD"), built.debt)
        assertEquals(usd("700"), (built.entry as Transaction.Expense).owed)
        assertNull(built.entry.let { it as Transaction.Expense }.owedDebtId)
    }
    @Test fun pickerOffersOnlyIOweInTheExpenseCurrency() {
        val debts = listOf(Debt(1, "Shop", DebtSide.I_OWE, "USD"),
            Debt(2, "Friend", DebtSide.OWED_TO_ME, "USD"), Debt(3, "Other", DebtSide.I_OWE, "EUR"))
        assertEquals(listOf(debts[0]), priced("300").offeredDebts(debts, "USD"))
    }
    @Test fun changingCurrencyClearsDebtSelectionAndPaidNow() {
        val changed = form.onCurrencyChanged(priced("300").copy(debtId = 7), "USD", "EUR")
        assertNull(changed.debtId)
        assertEquals("", changed.counterparty)
        assertEquals("", changed.paidNowInput)
    }
    @Test fun editLoadsPaidNowAsAmountMinusOwedAndKeepsDebtIdentity() {
        val loaded = form.fromEntry(expense().copy(owed = usd("700"), owedDebtId = 7),
            Debt(7, "Shop A", DebtSide.I_OWE, "USD"))
        assertTrue(loaded.enabled)
        assertEquals("300", loaded.paidNowInput)
        assertEquals(7L, loaded.debtId)
        assertEquals("Shop A", loaded.counterparty)
    }
    @Test fun switchedOffStateClearsStoredOwedAndDebt() {
        val loaded = expense().copy(owed = usd("700"), owedDebtId = 7)
        val saved = form.forSave(loaded, PaidLaterFormState()).entry as Transaction.Expense
        assertNull(saved.owed)
        assertNull(saved.owedDebtId)
    }
    @Test fun fractionalPaidNowRemainsExact() {
        assertEquals(usd("0.02"), priced("0.01", "0.03").owed)
    }
    @Test fun selectedDebtIdentitySurvivesSubmission() {
        val built = form.forSave(expense(), priced("300").copy(debtId = 7))
        assertEquals(7L, (built.entry as Transaction.Expense).owedDebtId)
        assertNull(built.debt)
    }
    @Test fun missingOrInvalidAmountClearsComputedOwed() {
        assertNull(form.recalculated(priced("300"), "", "USD").owed)
        assertNull(form.recalculated(priced("300"), "bad", "USD").owed)
        assertNull(form.recalculated(priced("300"), "1000", null).owed)
    }
    @Test fun unchangedCurrencyKeepsTheOwnersChoices() {
        val state = priced("300").copy(debtId = 7)
        assertEquals(state, form.onCurrencyChanged(state, "USD", "USD"))
    }
}
