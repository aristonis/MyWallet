package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.HealthLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/** The list rows of the debt kinds plus refunds: their type, the amount that moved and the amount that counts. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsListDebtKindsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val day = LocalDate.of(2026, 7, 1)
    private fun usd(a: String) = Money.of(a, "USD")

    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))
    private val salary = Category(id = 10, name = "Salary", kind = CategoryKind.INCOME)
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)
    private val interest = Category(id = 30, name = "Interest", kind = CategoryKind.EXPENSE)
    private val lost = Category(id = 40, name = "Lost debts", kind = CategoryKind.EXPENSE)

    private fun rowsOf(transactions: List<Transaction>, block: suspend (Map<Long, TransactionRow>) -> Unit) = runTest {
        val viewModel = TransactionsListViewModel(
            debts = FakeDebtRepository(),
            entries = FakeEntryRepository(transactions),
            accounts = FakeAccountRepository(listOf(cash)),
            categories = FakeCategoryRepository(listOf(salary, food, interest, lost)),
            currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "\u20AC", 2))),
            settings = FakeSettingsRepository(org.aristonis.mywallet.domain.model.Settings("USD")),
            observeLedgerHealth = HealthLedger().health(),
            moneyFormatter = MoneyFormatter(Locale.US),
            today = { LocalDate.of(2026, 8, 26) },
            savedStateHandle = SavedStateHandle(),
            defaultDispatcher = dispatcher,
            errors = RecordingErrorReporter(),
        )
        watch(viewModel)
        advanceUntilIdle()
        block(viewModel.state.value.rows.associateBy { it.id })
    }

    @Test
    fun everyDebtKindHasItsOwnRowType() {
        assertEquals(
            setOf(
                "INCOME", "EXPENSE", "TRANSFER", "BORROW", "LEND", "OWED_BEFORE",
                "REFUND",
                "CREDIT_PURCHASE", "EARNED_UNPAID", "REPAYMENT_PAID", "REPAYMENT_RECEIVED",
                "WRITE_OFF_LOSS", "WRITE_OFF_FORGIVEN",
            ),
            TransactionRowType.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun borrowLendAndOwedBeforeRowsHaveTheirTypeAndNoCountedAmount() = rowsOf(
        listOf(
            Transaction.Borrow(id = 1, accountId = 1, debtId = 7, amount = usd("5000"), date = day),
            Transaction.Lend(id = 2, accountId = 1, debtId = 8, amount = usd("300"), date = day),
            Transaction.OwedBefore(id = 3, debtId = 9, side = DebtSide.I_OWE, amount = usd("900"), date = day),
        ),
    ) { rows ->
        assertEquals(TransactionRowType.BORROW, rows.getValue(1).type)
        assertEquals(TransactionRowType.LEND, rows.getValue(2).type)
        assertEquals(TransactionRowType.OWED_BEFORE, rows.getValue(3).type)
        assertEquals(usd("5000"), rows.getValue(1).amount)
        assertEquals("5,000.00 USD", rows.getValue(1).amountDisplay)
        assertEquals("Cash", rows.getValue(1).accountName)
        assertEquals("Cash", rows.getValue(2).accountName)
        assertNull(rows.getValue(3).accountName)
        listOf(1L, 2L, 3L).forEach {
            assertNull(rows.getValue(it).countedAmount)
            assertNull(rows.getValue(it).countedAmountDisplay)
        }
    }

    @Test
    fun creditPurchaseAndEarnedUnpaidRowsCountTheirMainAmount() = rowsOf(
        listOf(
            Transaction.CreditPurchase(id = 4, debtId = 7, amount = usd("120.50"), categoryId = 20, date = day),
            Transaction.EarnedUnpaid(id = 5, debtId = 8, amount = usd("800"), categoryId = 10, date = day),
        ),
    ) { rows ->
        val purchase = rows.getValue(4)
        assertEquals(TransactionRowType.CREDIT_PURCHASE, purchase.type)
        assertEquals(usd("120.50"), purchase.amount)
        assertEquals(usd("120.50"), purchase.countedAmount)
        assertEquals("120.50 USD", purchase.countedAmountDisplay)
        assertEquals(CategoryLabel.Named("Food"), purchase.categoryLabel)
        assertNull(purchase.accountName)

        val earned = rows.getValue(5)
        assertEquals(TransactionRowType.EARNED_UNPAID, earned.type)
        assertEquals(usd("800"), earned.countedAmount)
        assertEquals(CategoryLabel.Named("Salary"), earned.categoryLabel)
    }

    @Test
    fun aRefundHasItsOwnRowTypeAndReducesTheCountedExpense() = rowsOf(
        listOf(
            Transaction.Refund(
                id = 10, accountId = 1, amount = usd("25"), categoryId = 20, date = day,
            ),
        ),
    ) { rows ->
        val refund = rows.getValue(10)
        assertEquals(TransactionRowType.REFUND, refund.type)
        assertEquals(usd("25"), refund.amount)
        assertEquals("Cash", refund.accountName)
        assertEquals(CategoryLabel.Named("Food"), refund.categoryLabel)
        assertEquals(usd("-25"), refund.countedAmount)
        assertEquals("-25.00 USD", refund.countedAmountDisplay)
    }

    @Test
    fun aRepaymentCountsOnlyItsInterest() = rowsOf(
        listOf(
            Transaction.Repayment(
                id = 6, side = DebtSide.I_OWE, accountId = 1, debtId = 7, accountAmount = usd("450"), principal = usd("400"),
                interest = usd("50"), interestCategoryId = 30, date = day,
            ),
            Transaction.Repayment(
                id = 7, side = DebtSide.OWED_TO_ME, accountId = 1, debtId = 8, accountAmount = usd("400"),
                principal = usd("400"), date = day,
            ),
        ),
    ) { rows ->
        val withInterest = rows.getValue(6)
        assertEquals(TransactionRowType.REPAYMENT_PAID, withInterest.type)
        assertEquals(usd("450"), withInterest.amount)
        assertEquals("Cash", withInterest.accountName)
        assertEquals(usd("50"), withInterest.countedAmount)
        assertEquals("50.00 USD", withInterest.countedAmountDisplay)

        val plain = rows.getValue(7)
        assertEquals(TransactionRowType.REPAYMENT_RECEIVED, plain.type)
        assertEquals(usd("400"), plain.amount)
        assertNull(plain.countedAmount)
        assertNull(plain.countedAmountDisplay)
    }

    @Test
    fun aWriteOffOfMoneyOwedToMeIsALossAndCountsTheWrittenOffAmount() = rowsOf(
        listOf(
            Transaction.WriteOff(
                id = 8, side = DebtSide.OWED_TO_ME, debtId = 7, amount = usd("75"), categoryId = 40,
                rateToBase = BigDecimal.ONE, date = day,
            ),
        ),
    ) { rows ->
        val row = rows.getValue(8)
        assertEquals(TransactionRowType.WRITE_OFF_LOSS, row.type)
        assertEquals(usd("75"), row.amount)
        assertEquals(usd("75"), row.countedAmount)
        assertEquals(CategoryLabel.Named("Lost debts"), row.categoryLabel)
    }

    @Test
    fun aWriteOffOfWhatIOweIsForgivenAndCountsTheWrittenOffAmount() = rowsOf(
        listOf(
            Transaction.WriteOff(
                id = 9, side = DebtSide.I_OWE, debtId = 7, amount = usd("60"), categoryId = 10,
                rateToBase = BigDecimal.ONE, date = day,
            ),
        ),
    ) { rows ->
        val row = rows.getValue(9)
        assertEquals(TransactionRowType.WRITE_OFF_FORGIVEN, row.type)
        assertEquals(usd("60"), row.amount)
        assertEquals(usd("60"), row.countedAmount)
        assertEquals(CategoryLabel.Named("Salary"), row.categoryLabel)
    }

    @Test
    fun incomeAndExpenseCountTheirAmountAndATransferCountsNothing() = rowsOf(
        listOf(
            Transaction.Income(id = 1, accountId = 1, amount = usd("50"), categoryId = 10, date = day),
            Transaction.Expense(id = 2, accountId = 1, amount = usd("20"), categoryId = 20, date = day),
            Transaction.Transfer(
                id = 3, sourceAccountId = 1, destAccountId = 2, sourceAmount = usd("10"),
                destAmount = Money.of("9", "EUR"), rateUsed = BigDecimal("0.9"), date = day,
            ),
        ),
    ) { rows ->
        assertEquals(usd("50"), rows.getValue(1).countedAmount)
        assertEquals(usd("20"), rows.getValue(2).countedAmount)
        assertNull(rows.getValue(3).countedAmount)
    }
}
