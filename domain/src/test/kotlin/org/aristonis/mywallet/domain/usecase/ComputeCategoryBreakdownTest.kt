package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.CategoryBreakdown
import org.aristonis.mywallet.domain.model.CategoryTotal
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ComputeCategoryBreakdownTest {

    private val july = DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))
    private val inJuly = LocalDate.of(2026, 7, 10)
    private fun usd(a: String) = Money.of(a, "USD")
    private fun eur(a: String) = Money.of(a, "EUR")

    private val currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))

    // Base USD. With no rates EUR is known but unrated, which is the "missing rate" condition.
    private fun usecase(transactions: List<Transaction>, rates: List<ExchangeRate> = emptyList()) =
        ComputeCategoryBreakdown(
            transactions = FakeTransactionRepository(transactions),
            fx = fakeFx(currencies = currencies, rates = rates, base = "USD"),
        )

    /** Unwrap the happy-path result; fails loudly if the breakdown was MissingRate. */
    private fun CategoryBreakdownResult.breakdown(): CategoryBreakdown =
        (this as CategoryBreakdownResult.Resolved).breakdown

    private fun List<CategoryTotal>.byId() = associateBy { it.categoryId }

    private fun expense(id: Long, amount: Money, category: Long, sub: Long? = null, date: LocalDate = inJuly) =
        Transaction.Expense(id = id, accountId = 1, amount = amount, categoryId = category, subCategoryId = sub, date = date)

    private fun income(id: Long, amount: Money, category: Long, sub: Long? = null, date: LocalDate = inJuly) =
        Transaction.Income(id = id, accountId = 1, amount = amount, categoryId = category, subCategoryId = sub, date = date)

    @Test
    fun splitsIncomeAndExpenseByCategory() = runTest {
        val txs = listOf(
            expense(1, usd("30"), category = 7),
            expense(2, usd("20"), category = 7),
            expense(3, usd("15"), category = 9),
            income(4, usd("500"), category = 1),
        )
        val breakdown = usecase(txs).invoke(july).first().breakdown()

        val expenses = breakdown.expense.byId()
        assertEquals(usd("50"), expenses.getValue(7).total)
        assertEquals(usd("15"), expenses.getValue(9).total)
        assertNull(expenses[1])
        assertEquals(usd("500"), breakdown.income.byId().getValue(1).total)
        assertNull(breakdown.income.byId()[7])
    }

    @Test
    fun subCategoriesSumToTheParent() = runTest {
        val txs = listOf(
            expense(1, usd("12.50"), category = 7, sub = 71),
            expense(2, usd("7.50"), category = 7, sub = 71),
            expense(3, usd("30"), category = 7, sub = 72),
            expense(4, usd("5"), category = 7, sub = null),
        )
        val food = usecase(txs).invoke(july).first().breakdown().expense.byId().getValue(7)
        val subs = food.subCategories.associateBy { it.subCategoryId }

        assertEquals(usd("55"), food.total)
        assertEquals(usd("20"), subs.getValue(71).total)
        assertEquals(usd("30"), subs.getValue(72).total)
        assertEquals(usd("5"), subs.getValue(null).total) // the "no sub-category" remainder
        assertEquals(food.total, food.subCategories.fold(usd("0")) { acc, s -> acc + s.total })
    }

    @Test
    fun categoryWithoutSubCategoriesHasOnlyTheRemainder() = runTest {
        val txs = listOf(expense(1, usd("9"), category = 9))
        val other = usecase(txs).invoke(july).first().breakdown().expense.byId().getValue(9)

        assertEquals(1, other.subCategories.size)
        assertNull(other.subCategories.single().subCategoryId)
        assertEquals(usd("9"), other.subCategories.single().total)
    }

    @Test
    fun clearedSubCategoryFallsIntoTheRemainder() = runTest {
        // Deleting a sub-category clears the stored link, so its spend arrives with no sub-category.
        val txs = listOf(
            expense(1, usd("10"), category = 7, sub = 71),
            expense(2, usd("4"), category = 7, sub = null),
            expense(3, usd("6"), category = 7, sub = null),
        )
        val subs = usecase(txs).invoke(july).first().breakdown().expense.byId().getValue(7)
            .subCategories.associateBy { it.subCategoryId }

        assertEquals(usd("10"), subs.getValue(null).total)
    }

    @Test
    fun transfersAreExcluded() = runTest {
        val txs = listOf(
            expense(1, usd("10"), category = 7),
            Transaction.Transfer(
                id = 2, sourceAccountId = 1, destAccountId = 2,
                sourceAmount = usd("99"), destAmount = usd("99"),
                rateUsed = BigDecimal.ONE, date = inJuly,
            ),
        )
        val breakdown = usecase(txs).invoke(july).first().breakdown()

        assertEquals(listOf(7L), breakdown.expense.map { it.categoryId })
        assertTrue(breakdown.income.isEmpty())
    }

    @Test
    fun mixedCurrenciesDoNotDrift() = runTest {
        // 0.33 EUR at 1.1 is 0.363, which rounds to 0.36 USD per transaction. Converting the 0.66
        // EUR sum instead would give 0.726 -> 0.73, a cent more than the children add up to.
        val txs = listOf(
            expense(1, eur("0.33"), category = 7, sub = 71),
            expense(2, eur("0.33"), category = 7, sub = 72),
        )
        val food = usecase(txs, rates = listOf(ExchangeRate("EUR", BigDecimal("1.1"))))
            .invoke(july).first().breakdown().expense.byId().getValue(7)

        assertEquals(usd("0.72"), food.total)
        assertEquals(food.total, food.subCategories.fold(usd("0")) { acc, s -> acc + s.total })
    }

    @Test
    fun missingIncomeRateIsReported() = runTest {
        val txs = listOf(income(1, eur("100"), category = 1))
        assertEquals(CategoryBreakdownResult.MissingRate("EUR"), usecase(txs).invoke(july).first())
    }

    @Test
    fun missingExpenseRateIsReported() = runTest {
        val txs = listOf(expense(1, eur("30"), category = 7))
        assertEquals(CategoryBreakdownResult.MissingRate("EUR"), usecase(txs).invoke(july).first())
    }

    @Test
    fun outOfRangeForeignEntryWithNoRateStillResolves() = runTest {
        val txs = listOf(expense(1, eur("30"), category = 7, date = LocalDate.of(2026, 6, 5)))
        val breakdown = usecase(txs).invoke(july).first().breakdown()

        assertTrue(breakdown.expense.isEmpty())
        assertTrue(breakdown.income.isEmpty())
    }

    @Test
    fun boundsAreInclusive() = runTest {
        val txs = listOf(
            expense(1, usd("1"), category = 7, date = july.start),
            expense(2, usd("2"), category = 7, date = july.endInclusive),
            expense(3, usd("4"), category = 7, date = july.endInclusive.plusDays(1)),
        )
        assertEquals(usd("3"), usecase(txs).invoke(july).first().breakdown().expense.single().total)
    }

    @Test
    fun categoriesComeBiggestFirstWithTiesByIdAscending() = runTest {
        val txs = listOf(
            expense(1, usd("10"), category = 9),
            expense(2, usd("10"), category = 7),
            expense(3, usd("50"), category = 12),
        )
        val order = usecase(txs).invoke(july).first().breakdown().expense.map { it.categoryId }

        assertEquals(listOf(12L, 7L, 9L), order)
    }

    @Test
    fun subCategoriesComeBiggestFirstWithTheRemainderLastOnTies() = runTest {
        val txs = listOf(
            expense(1, usd("5"), category = 7, sub = null),
            expense(2, usd("5"), category = 7, sub = 72),
            expense(3, usd("5"), category = 7, sub = 71),
            expense(4, usd("8"), category = 7, sub = 73),
        )
        val order = usecase(txs).invoke(july).first().breakdown().expense.single()
            .subCategories.map { it.subCategoryId }

        assertEquals(listOf(73L, 71L, 72L, null), order)
    }

    @Test
    fun entriesInOneSubCategoryAreConvertedOneByOne() = runTest {
        // Two 0.33 EUR in the SAME sub-category: 0.36 + 0.36 = 0.72. Converting the 0.66 EUR sum
        // of the bucket would give 0.73.
        val txs = listOf(
            expense(1, eur("0.33"), category = 7, sub = 71),
            expense(2, eur("0.33"), category = 7, sub = 71),
        )
        val food = usecase(txs, rates = listOf(ExchangeRate("EUR", BigDecimal("1.1"))))
            .invoke(july).first().breakdown().expense.single()

        assertEquals(usd("0.72"), food.subCategories.single().total)
        assertEquals(usd("0.72"), food.total)
    }

    @Test
    fun readsOnlyTheRequestedRangeFromTheRepository() = runTest {
        val repo = FakeTransactionRepository(listOf(expense(1, usd("3"), category = 7)))
        val usecase = ComputeCategoryBreakdown(repo, fakeFx(currencies = currencies, base = "USD"))

        usecase(july).first()

        assertEquals(listOf(july), repo.observedRanges)
        assertEquals(0, repo.observeAllCalls)
    }
}
