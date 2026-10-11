package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.CategoryBreakdown
import org.aristonis.mywallet.domain.model.CategoryTotal
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RefundTrackingTest {

    private val september = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
    private val october = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
    private val currencies = listOf(Currency("USD", "$", 2))

    @Test
    fun octoberRefundReducesElectronicsWithoutBecomingIncomeAndAllTimeKeepsTheNetCost() = runTest {
        val entries = listOf(
            Transaction.Expense(
                id = 1,
                accountId = 1,
                amount = usd("1000"),
                categoryId = ELECTRONICS,
                date = LocalDate.of(2026, 9, 8),
            ),
            Transaction.Refund(
                id = 2,
                accountId = 1,
                amount = usd("200"),
                categoryId = ELECTRONICS,
                date = LocalDate.of(2026, 10, 3),
            ),
        )
        val tracking = tracking(entries)

        val septemberBreakdown = tracking(september).first().breakdown()
        val octoberBreakdown = tracking(october).first().breakdown()
        val allTimeBreakdown = tracking(DateRange.ALL_TIME).first().breakdown()

        assertEquals(usd("1000"), septemberBreakdown.expense.byId().getValue(ELECTRONICS).total)
        assertEquals(usd("-200"), octoberBreakdown.expense.byId().getValue(ELECTRONICS).total)
        assertTrue(octoberBreakdown.income.isEmpty())
        assertEquals(usd("800"), allTimeBreakdown.expense.byId().getValue(ELECTRONICS).total)
    }

    @Test
    fun aRefundInAMonthWithNoOtherSpendingTakesTheMonthsExpenseTotalDownByTheRefund() = runTest {
        val tracking = tracking(
            listOf(
                Transaction.Expense(
                    id = 1, accountId = 1, amount = usd("1000"), categoryId = ELECTRONICS,
                    date = LocalDate.of(2026, 9, 8),
                ),
                Transaction.Expense(
                    id = 2, accountId = 1, amount = usd("50"), categoryId = GROCERIES,
                    date = LocalDate.of(2026, 10, 2),
                ),
                Transaction.Refund(
                    id = 3, accountId = 1, amount = usd("200"), categoryId = ELECTRONICS,
                    date = LocalDate.of(2026, 10, 3),
                ),
            ),
        )

        val summary = tracking(october).first().breakdown().summary

        assertEquals(usd("-150"), summary.expense)
        assertEquals(usd("0"), summary.income)
        assertEquals(usd("150"), summary.net)
    }

    @Test
    fun aLoneRefundShowsTheMonthExpenseAsMinusTheRefundAndNoIncome() = runTest {
        val tracking = tracking(
            listOf(
                Transaction.Refund(
                    id = 1, accountId = 1, amount = usd("200"), categoryId = ELECTRONICS,
                    date = LocalDate.of(2026, 10, 3),
                ),
            ),
        )

        val summary = tracking(october).first().breakdown().summary

        assertEquals(usd("-200"), summary.expense)
        assertEquals(usd("0"), summary.income)
    }

    @Test
    fun refundAgainstAnIncomeCategoryIsRefusedAndNothingIsSaved() = runTest {
        val rig = SaveEntryRig()
        val refund = Transaction.Refund(
            accountId = EntryWorld.usdAccount.id,
            amount = usd("200"),
            categoryId = EntryWorld.incomeCategory.id,
            date = LocalDate.of(2026, 10, 3),
        )

        val failure = runCatching { rig.saveEntry(refund, typedRates = emptyMap()) }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.CategoryWrongKind)
        assertEquals(EntryWorld.incomeCategory.id, (failure as WalletException.CategoryWrongKind).categoryId)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    private fun tracking(entries: List<Transaction>): ComputeCategoryBreakdown =
        ComputeCategoryBreakdown(
            ledger = FakeLedgerQueries(FakeEntryRepository(entries)),
            fx = fakeFx(currencies = currencies, rates = emptyList(), base = "USD"),
        )

    private fun usd(amount: String) = Money.of(amount, "USD")

    private fun CategoryBreakdownResult.breakdown(): CategoryBreakdown =
        (this as CategoryBreakdownResult.Resolved).breakdown

    private fun List<CategoryTotal>.byId(): Map<Long, CategoryTotal> = associateBy { it.categoryId }

    private companion object {
        const val ELECTRONICS = 8L
        const val GROCERIES = 9L
    }
}
