package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class ExpenseFormRulePaidLaterTest {
    private val parser = MoneyParser(Locale.US)
    private val day = LocalDate.of(2026, 10, 17)
    private val usd = Account(1, "Bank", "bank", "USD", Money.zero("USD"))
    private val eur = Account(2, "Euro", "bank", "EUR", Money.zero("EUR"))
    private val loaded = Transaction.Expense(id = 10, accountId = 1, amount = Money.of("1000", "USD"),
        categoryId = 6, date = day, owed = Money.of("700", "USD"), owedDebtId = 7)
    @Test fun editedDraftNeverCarriesStaleOwedOrDebtIdentity() {
        val inputs = ExpenseFormRule.load(loaded, parser).copy(amountInput = "100")
        val edited = ExpenseFormRule.build(inputs, listOf(usd), parser, day, "", loaded).entry as Transaction.Expense
        assertEquals(Money.of("100", "USD"), edited.amount)
        assertNull(edited.owed)
        assertNull(edited.owedDebtId)
    }
    @Test fun changingAccountCurrencyClearsOwedBeforeConstructingTheDraft() {
        val inputs = ExpenseFormRule.load(loaded, parser).copy(accountId = 2)
        val edited = ExpenseFormRule.build(inputs, listOf(usd, eur), parser, day, "", loaded).entry as Transaction.Expense
        assertEquals("EUR", edited.amount.currencyCode)
        assertNull(edited.owed)
        assertNull(edited.owedDebtId)
    }
}
