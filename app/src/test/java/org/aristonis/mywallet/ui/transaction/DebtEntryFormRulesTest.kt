package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.MissingSelectionException
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class DebtEntryFormRulesTest {
    private val parser = MoneyParser(Locale.US)
    private val day = DebtFormTestFixture.day
    private val accounts = listOf(DebtFormTestFixture.account(1, "USD"), DebtFormTestFixture.account(2, "EUR"))
    private val inputs = EntryFormInputs(accountId = 1, categoryId = 6, amountInput = "50",
        counterparty = " Ali ", currencyCode = "EUR", debtSide = DebtSide.I_OWE)

    @Test fun borrowingBuildsInTheActiveAccountCurrencyWithAnExactDebtReference() {
        val built = EntryFormRules.forType(TransactionType.BORROW).build(inputs, accounts, parser, day, "loan")
        assertEquals(Transaction.Borrow(accountId = 1, debtId = 0, amount = DebtFormTestFixture.usd("50"), date = day, note = "loan"), built.entry)
        assertEquals(DebtRef(" Ali ", DebtSide.I_OWE, "USD"), built.debt)
    }

    @Test fun lendingBuildsInTheAccountCurrencyOnTheOwedToMeSide() {
        val built = EntryFormRules.forType(TransactionType.LEND).build(inputs, accounts, parser, day, "")
        assertEquals(Transaction.Lend(accountId = 1, debtId = 0, amount = DebtFormTestFixture.usd("50"), date = day), built.entry)
        assertEquals(DebtRef(" Ali ", DebtSide.OWED_TO_ME, "USD"), built.debt)
    }

    @Test fun owedBeforeUsesTheExplicitCurrencyAndSideWithoutAnAccountOrCategory() {
        val form = inputs.copy(accountId = null, categoryId = null, debtSide = DebtSide.OWED_TO_ME)
        val rule = EntryFormRules.forType(TransactionType.OWED_BEFORE)
        assertTrue(rule.isSubmittable(form))
        val built = rule.build(form, emptyList(), parser, day, "")
        assertEquals(Transaction.OwedBefore(debtId = 0, side = DebtSide.OWED_TO_ME,
            amount = DebtFormTestFixture.eur("50"), date = day), built.entry)
        assertEquals(DebtRef(" Ali ", DebtSide.OWED_TO_ME, "EUR"), built.debt)
    }

    @Test fun aCreditPurchaseUsesExplicitCurrencyAndExpenseCategoryEvenWithAStaleAccount() {
        val form = inputs.copy(accountId = 99)
        val built = EntryFormRules.forType(TransactionType.CREDIT_PURCHASE).build(form, emptyList(), parser, day, "")
        assertEquals(Transaction.CreditPurchase(debtId = 0, amount = DebtFormTestFixture.eur("50"), categoryId = 6, date = day), built.entry)
        assertEquals(DebtRef(" Ali ", DebtSide.I_OWE, "EUR"), built.debt)
    }

    @Test fun earnedUnpaidUsesExplicitCurrencyAndIncomeCategoryWithoutAnAccount() {
        val form = inputs.copy(accountId = null, categoryId = 5)
        val built = EntryFormRules.forType(TransactionType.EARNED_UNPAID).build(form, emptyList(), parser, day, "")
        assertEquals(Transaction.EarnedUnpaid(debtId = 0, amount = DebtFormTestFixture.eur("50"), categoryId = 5, date = day), built.entry)
        assertEquals(DebtRef(" Ali ", DebtSide.OWED_TO_ME, "EUR"), built.debt)
    }

    @Test fun borrowingAndLendingRefuseAMissingAccountInsteadOfReturningSilently() {
        listOf(TransactionType.BORROW, TransactionType.LEND).forEach { type ->
            val failure = assertThrows(MissingSelectionException::class.java) {
                EntryFormRules.forType(type).build(inputs.copy(accountId = null), accounts, parser, day, "")
            }
            assertEquals(UiMessage.AccountRequired, failure.uiMessage)
        }
    }

    @Test fun everyNewRuleRequiresACounterparty() {
        debtTypes.forEach { type ->
            assertFalse(EntryFormRules.forType(type).isSubmittable(inputs.copy(counterparty = " ")))
        }
    }

    @Test fun loadingEachNewKindKeepsItsDebtAndActualAmountCurrency() {
        entries.forEach { entry ->
            val rule = EntryFormRules.forEntry(entry)!!
            val loaded = rule.load(entry, parser)
            assertEquals(7L, loaded.debtId)
            assertEquals("USD", loaded.currencyCode)
            assertEquals("50", loaded.amountInput)
            assertEquals(rule.type, EntryFormRules.forType(rule.type).type)
        }
    }

    @Test fun editingEachNewKindKeepsItsStoredIdAndDebtTarget() {
        entries.forEach { entry ->
            val rule = EntryFormRules.forEntry(entry)!!
            val loaded = rule.load(entry, parser).copy(counterparty = "Ali", amountInput = "60")
            val built = rule.build(loaded, accounts, parser, day, "receipt", entry)
            assertEquals(10L, built.entry.id)
            assertEquals("receipt", built.entry.note)
            assertEquals(7L, rule.load(built.entry, parser).debtId)
        }
    }

    @Test fun editingAnUnpaidCategoryEntryKeepsItsHistoricalRateInTheBuiltEntry() {
        val original = Transaction.EarnedUnpaid(id = 10, debtId = 7, amount = DebtFormTestFixture.usd("50"),
            categoryId = 5, date = day, rateToBase = BigDecimal("12000"))
        val rule = EntryFormRules.forEntry(original)!!
        val built = rule.build(rule.load(original, parser).copy(counterparty = "Ali"), accounts, parser, day, "receipt", original)
        assertEquals(BigDecimal("12000"), (built.entry as Transaction.EarnedUnpaid).rateToBase)
    }

    private val debtTypes = listOf(TransactionType.BORROW, TransactionType.LEND, TransactionType.OWED_BEFORE,
        TransactionType.CREDIT_PURCHASE, TransactionType.EARNED_UNPAID)
    private val entries: List<Transaction> = listOf(
        Transaction.Borrow(10, 1, 7, DebtFormTestFixture.usd("50"), day),
        Transaction.Lend(10, 1, 7, DebtFormTestFixture.usd("50"), day),
        Transaction.OwedBefore(10, 7, DebtSide.I_OWE, DebtFormTestFixture.usd("50"), day),
        Transaction.CreditPurchase(10, 7, DebtFormTestFixture.usd("50"), 6, date = day),
        Transaction.EarnedUnpaid(10, 7, DebtFormTestFixture.usd("50"), 5, date = day))
}
