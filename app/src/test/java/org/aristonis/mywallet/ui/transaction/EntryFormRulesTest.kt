package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.MissingSelectionException
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * One rule per entry type, shared by the add and the edit form: the categories it offers, when the
 * form is submittable, how an entry is built from the inputs and loaded back into them. An edit keeps
 * the loaded entry's id and the fields the form never shows.
 */
class EntryFormRulesTest {

    private val parser = MoneyParser(Locale.US)
    private val day = LocalDate.of(2026, 7, 3)

    private val usdAccount = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))
    private val eurAccount = Account(id = 2, name = "Bank", typeKey = "bank", currencyCode = "EUR", openingBalance = Money.zero("EUR"))
    private val accounts = listOf(usdAccount, eurAccount)

    private val salary = Category(id = 1, name = "Salary", kind = CategoryKind.INCOME)
    private val bonus = Category(id = 2, name = "Bonus", kind = CategoryKind.INCOME, parentId = 1)
    private val food = Category(id = 3, name = "Food", kind = CategoryKind.EXPENSE)
    private val snacks = Category(id = 4, name = "Snacks", kind = CategoryKind.EXPENSE, parentId = 3)
    private val rent = Category(id = 5, name = "Rent", kind = CategoryKind.EXPENSE)
    private val all = listOf(salary, bonus, food, snacks, rent)

    private fun rule(type: TransactionType) = EntryFormRules.forType(type)

    // ---- the rule of each type ----

    @Test
    fun eachTypeHasItsOwnRule() {
        TransactionType.entries.forEach { assertEquals(it, rule(it).type) }
    }

    @Test
    fun incomeOffersTopLevelIncomeCategoriesOnly() {
        assertEquals(listOf(salary), rule(TransactionType.INCOME).categoriesOffered(all))
    }

    @Test
    fun expenseOffersTopLevelExpenseCategoriesOnly() {
        assertEquals(listOf(food, rent), rule(TransactionType.EXPENSE).categoriesOffered(all))
    }

    @Test
    fun transferOffersNoCategories() {
        assertEquals(emptyList<Category>(), rule(TransactionType.TRANSFER).categoriesOffered(all))
    }

    // ---- what makes the form submittable ----

    @Test
    fun incomeAndExpenseNeedAnAccountACategoryAndAnAmount() {
        listOf(TransactionType.INCOME, TransactionType.EXPENSE).forEach { type ->
            val r = rule(type)
            val full = EntryFormInputs(accountId = 1, categoryId = 3, amountInput = "10")
            assertTrue(r.isSubmittable(full))
            assertFalse(r.isSubmittable(full.copy(accountId = null)))
            assertFalse(r.isSubmittable(full.copy(categoryId = null)))
            assertFalse(r.isSubmittable(full.copy(amountInput = "")))
            assertFalse(r.isSubmittable(full.copy(amountInput = "   ")))
        }
    }

    @Test
    fun aTransferNeedsTwoDifferentAccountsAndAnAmountAndNoCategory() {
        val r = rule(TransactionType.TRANSFER)
        val full = EntryFormInputs(accountId = 1, destAccountId = 2, amountInput = "10")
        assertTrue(r.isSubmittable(full))
        assertFalse(r.isSubmittable(full.copy(accountId = null)))
        assertFalse(r.isSubmittable(full.copy(destAccountId = null)))
        assertFalse(r.isSubmittable(full.copy(destAccountId = 1)))
        assertFalse(r.isSubmittable(full.copy(amountInput = "")))
    }

    // ---- building the entry (add) ----

    @Test
    fun addBuildsAnIncomeInTheSelectedAccountsCurrency() {
        val inputs = EntryFormInputs(accountId = 2, categoryId = 1, subCategoryId = 2, amountInput = "50")

        val built = rule(TransactionType.INCOME).build(inputs, accounts, parser, day, "  June pay ").entry

        assertEquals(
            Transaction.Income(
                accountId = 2, amount = Money.of("50", "EUR"), categoryId = 1, subCategoryId = 2,
                date = day, note = "June pay",
            ),
            built,
        )
    }

    @Test
    fun addBuildsAnExpenseAndTurnsABlankNoteIntoNull() {
        val inputs = EntryFormInputs(accountId = 1, categoryId = 3, amountInput = "12.50")

        val built = rule(TransactionType.EXPENSE).build(inputs, accounts, parser, day, "   ").entry

        assertEquals(
            Transaction.Expense(accountId = 1, amount = Money.of("12.50", "USD"), categoryId = 3, date = day, note = null),
            built,
        )
        assertNull((built as Transaction.Expense).note)
    }

    @Test
    fun addBuildsATransferWithAPlaceholderDestinationAmountAtRateOne() {
        val inputs = EntryFormInputs(accountId = 1, destAccountId = 2, amountInput = "100")

        val built = rule(TransactionType.TRANSFER).build(inputs, accounts, parser, day, "").entry

        assertEquals(
            Transaction.Transfer(
                sourceAccountId = 1, destAccountId = 2, sourceAmount = Money.of("100", "USD"),
                destAmount = Money.of("100", "EUR"), rateUsed = BigDecimal.ONE, date = day, note = null,
            ),
            built,
        )
    }

    @Test
    fun buildingWithAMissingChoiceNamesWhatIsMissing() {
        fun refusal(type: TransactionType, inputs: EntryFormInputs, loaded: Transaction? = null): UiMessage =
            assertThrows(MissingSelectionException::class.java) {
                rule(type).build(inputs, accounts, parser, day, "", loaded)
            }.uiMessage

        assertEquals(UiMessage.CategoryRequired, refusal(TransactionType.EXPENSE, EntryFormInputs(accountId = 1, amountInput = "5")))
        assertEquals(UiMessage.AccountRequired, refusal(TransactionType.EXPENSE, EntryFormInputs(categoryId = 3, amountInput = "5")))
        assertEquals(
            UiMessage.DestinationAccountRequired,
            refusal(TransactionType.TRANSFER, EntryFormInputs(accountId = 1, amountInput = "5")),
        )
    }

    // ---- building the entry (edit) ----

    @Test
    fun editKeepsTheIdAndTheStoredRateOfAnIncome() {
        val loaded = Transaction.Income(
            id = 9, accountId = 1, amount = Money.of("50", "USD"), categoryId = 1, date = LocalDate.of(2026, 6, 1),
            note = "old", rateToBase = BigDecimal("3.75"),
        )
        val inputs = EntryFormInputs(accountId = 2, categoryId = 1, subCategoryId = null, amountInput = "70")

        val built = rule(TransactionType.INCOME).build(inputs, accounts, parser, day, " new ", loaded).entry

        assertEquals(
            loaded.copy(accountId = 2, amount = Money.of("70", "EUR"), subCategoryId = null, date = day, note = "new"),
            built,
        )
    }

    @Test
    fun editKeepsTheIdAndTheStoredRateOfAnExpense() {
        val loaded = Transaction.Expense(
            id = 8, accountId = 1, amount = Money.of("5", "USD"), categoryId = 3, date = LocalDate.of(2026, 6, 1),
            rateToBase = BigDecimal("2.5"),
        )
        val inputs = EntryFormInputs(accountId = 1, categoryId = 5, subCategoryId = null, amountInput = "6")

        val built = rule(TransactionType.EXPENSE).build(inputs, accounts, parser, day, "", loaded).entry

        assertEquals(loaded.copy(amount = Money.of("6", "USD"), categoryId = 5, date = day), built)
    }

    @Test
    fun editOfATransferChangesOnlyTheSourceAmountDateAndNote() {
        val loaded = Transaction.Transfer(
            id = 7, sourceAccountId = 1, destAccountId = 2, sourceAmount = Money.of("40", "USD"),
            destAmount = Money.of("37", "EUR"), rateUsed = BigDecimal("0.925"), date = LocalDate.of(2026, 6, 1),
        )
        // The accounts typed into the inputs must be ignored: a transfer's pair is locked.
        val inputs = EntryFormInputs(accountId = 2, destAccountId = 1, amountInput = "60")

        val built = rule(TransactionType.TRANSFER).build(inputs, accounts, parser, day, "moved", loaded).entry

        assertEquals(loaded.copy(sourceAmount = Money.of("60", "USD"), date = day, note = "moved"), built)
    }

    // ---- loading an entry into the inputs ----

    @Test
    fun loadingAnIncomeFillsAccountCategoryAndAmount() {
        val entry = Transaction.Income(
            id = 5, accountId = 2, amount = Money.of("50.5", "EUR"), categoryId = 1, subCategoryId = 2, date = day,
        )

        val inputs = EntryFormRules.forEntry(entry)!!.load(entry, parser)

        assertEquals(
            EntryFormInputs(
                accountId = 2, categoryId = 1, subCategoryId = 2, destAccountId = null,
                amountInput = parser.toInputString(BigDecimal("50.5")),
            ),
            inputs,
        )
    }

    @Test
    fun loadingAnExpenseFillsAccountCategoryAndAmount() {
        val entry = Transaction.Expense(id = 6, accountId = 1, amount = Money.of("12", "USD"), categoryId = 3, date = day)

        val inputs = EntryFormRules.forEntry(entry)!!.load(entry, parser)

        assertEquals(1L, inputs.accountId)
        assertEquals(3L, inputs.categoryId)
        assertNull(inputs.subCategoryId)
        assertEquals(parser.toInputString(BigDecimal("12")), inputs.amountInput)
    }

    @Test
    fun loadingATransferFillsBothAccountsAndTheSourceAmountButNoCategory() {
        val entry = Transaction.Transfer(
            id = 7, sourceAccountId = 1, destAccountId = 2, sourceAmount = Money.of("40", "USD"),
            destAmount = Money.of("37", "EUR"), rateUsed = BigDecimal("0.925"), date = day,
        )

        val inputs = EntryFormRules.forEntry(entry)!!.load(entry, parser)

        assertEquals(
            EntryFormInputs(
                accountId = 1, categoryId = null, subCategoryId = null, destAccountId = 2,
                amountInput = parser.toInputString(BigDecimal("40")),
            ),
            inputs,
        )
    }

    @Test
    fun theRuleOfAnEntryIsTheRuleOfItsType() {
        val income = Transaction.Income(accountId = 1, amount = Money.of("1", "USD"), categoryId = 1, date = day)
        val expense = Transaction.Expense(accountId = 1, amount = Money.of("1", "USD"), categoryId = 3, date = day)
        val transfer = Transaction.Transfer(
            sourceAccountId = 1, destAccountId = 2, sourceAmount = Money.of("1", "USD"),
            destAmount = Money.of("1", "EUR"), rateUsed = BigDecimal.ONE, date = day,
        )
        assertEquals(TransactionType.INCOME, EntryFormRules.forEntry(income)!!.type)
        assertEquals(TransactionType.EXPENSE, EntryFormRules.forEntry(expense)!!.type)
        assertEquals(TransactionType.TRANSFER, EntryFormRules.forEntry(transfer)!!.type)
    }

    @Test
    fun repaymentAndWriteOffStillHaveNoEditableFormRule() {
        val repayment = Transaction.Repayment(accountId = 1, debtId = 1, side = DebtSide.I_OWE,
            accountAmount = Money.of("5", "USD"), principal = Money.of("5", "USD"), date = day)
        val writeOff = Transaction.WriteOff(debtId = 1, side = DebtSide.I_OWE,
            amount = Money.of("5", "USD"), categoryId = 1, date = day)
        assertNull(EntryFormRules.forEntry(repayment))
        assertNull(EntryFormRules.forEntry(writeOff))
    }
}
