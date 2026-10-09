package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Tracking and net worth read legs. Tracking converts each category leg at the rate stored on it;
 * net worth converts balances at today's saved rate.
 *
 * Assumed (see also ComputeCategoryBreakdownTest):
 * - `ComputeCategoryBreakdown(ledger: LedgerQueries, fx: FxRepository)`.
 * - A category leg counts on the side its entry kind's tracking rule gives for its role, as 1.1.1
 *   counted an entry by its type: never by its category's kind, and a leg on a deleted category
 *   still counts. A kind with no recipe, or a role its recipe has no rule for, throws
 *   `WalletException.UnreadableRow(entryId, entryKind)` out of the flow.
 * - A leg in a non-base currency with a null stored rate gives `CategoryBreakdownResult.MissingRate(code)`
 *   even when a rate is saved today; a base-currency leg needs no rate.
 */
class TrackingFromLegsTest {

    private val july = DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))
    private val day = LocalDate.of(2026, 7, 10)
    private val currencies = listOf(Currency("SAR", "SAR", 2), Currency("USD", "$", 2))

    private fun sar(a: String) = Money.of(a, "SAR")
    private fun usd(a: String) = Money.of(a, "USD")
    private fun rateOf(code: String, rate: String) = ExchangeRate(code, BigDecimal(rate))

    private fun snapshot(rate: String) = FxSnapshot("SAR", mapOf("USD" to BigDecimal(rate)), currencies)

    private fun breakdown(entries: FakeEntryRepository, rate: String = "3.75") =
        ComputeCategoryBreakdown(FakeLedgerQueries(entries), fakeFx(currencies, listOf(rateOf("USD", rate)), "SAR"))

    private fun expense(amount: Money, rate: String?) = Transaction.Expense(
        id = 1, accountId = 1, amount = amount, categoryId = 7, date = day, rateToBase = rate?.let(::BigDecimal),
    )

    private fun categoryLeg(role: LegRole, category: Long, amount: Money, rate: String? = null) =
        Leg(role, 0, LegTarget.Category(category, null), amount, rate?.let(::BigDecimal))

    private fun rawEntry(id: Long, kind: String, vararg legs: Leg) =
        EntryParts(EntryHeader(id, kind, day, null), legs.toList())

    private fun CategoryBreakdownResult.resolved() = (this as CategoryBreakdownResult.Resolved).breakdown

    @Test
    fun trackingUsesTheStoredRateUsd100At375Stays375AfterTheSavedRateBecomes380() = runTest {
        val entries = FakeEntryRepository(listOf(expense(usd("100"), rate = "3.75")))
        val fx = fakeFx(currencies, listOf(rateOf("USD", "3.75")), "SAR")
        val tracking = ComputeCategoryBreakdown(FakeLedgerQueries(entries), fx)
        assertEquals(sar("375"), tracking(july).first().resolved().summary.expense)

        fx.emit(snapshot("3.80"))

        assertEquals(sar("375"), tracking(july).first().resolved().summary.expense)
    }

    @Test
    fun netWorthUsesTodaysRate380() = runTest {
        val account = Account(id = 1, name = "Dollars", typeKey = "cash", currencyCode = "USD", openingBalance = usd("100"))
        val entries = FakeEntryRepository()
        val fx = fakeFx(currencies, listOf(rateOf("USD", "3.75")), "SAR")
        val netWorth = ComputeNetWorth(GetAccountBalances(FakeAccountRepository(listOf(account)), FakeLedgerQueries(entries)), fx)
        assertEquals(NetWorth.Amount(sar("375")), netWorth().first())

        fx.emit(snapshot("3.80"))

        assertEquals(NetWorth.Amount(sar("380")), netWorth().first())
    }

    @Test
    fun aLegOnADeletedCategoryStillCountsOnItsEntryTypesSide() = runTest {
        val entries = FakeEntryRepository()
        entries.seedRaw(rawEntry(1, "EXPENSE", categoryLeg(LegRole.MAIN, 99, sar("10"), "1")))

        val summary = breakdown(entries)(july).first().resolved().summary

        assertEquals(sar("10"), summary.expense)
        assertEquals(99L, breakdownOf(entries).expense.single().categoryId)
    }

    @Test
    fun anIncomeFiledUnderAnExpenseCategoryCountsAsIncome() = runTest {
        val entries = FakeEntryRepository()
        entries.seedRaw(rawEntry(1, "INCOME", categoryLeg(LegRole.MAIN, 7, sar("100"), "1")))
        entries.seedRaw(rawEntry(2, "EXPENSE", categoryLeg(LegRole.MAIN, 1, sar("30"), "1")))

        val summary = breakdown(entries)(july).first().resolved().summary

        assertEquals(sar("100"), summary.income)
        assertEquals(sar("30"), summary.expense)
    }

    @Test
    fun anEntryOfAKindWithNoRecipeIsUnreadable() = runTest {
        val entries = FakeEntryRepository()
        entries.seedRaw(rawEntry(3, "SOME_FUTURE_KIND", categoryLeg(LegRole.MAIN, 7, sar("4"), "1")))

        val failure = runCatching { breakdown(entries)(july).first() }.exceptionOrNull()

        assertTrue("expected UnreadableRow, got $failure", failure is WalletException.UnreadableRow)
        assertEquals(3L, (failure as WalletException.UnreadableRow).id)
        assertEquals("SOME_FUTURE_KIND", failure.kind)
    }

    @Test
    fun aCategoryLegInARoleItsKindHasNoRuleForIsUnreadable() = runTest {
        val entries = FakeEntryRepository()
        entries.seedRaw(rawEntry(2, "TRANSFER", categoryLeg(LegRole.FEE, 7, sar("6"), "1")))

        val failure = runCatching { breakdown(entries)(july).first() }.exceptionOrNull()

        assertTrue("expected UnreadableRow, got $failure", failure is WalletException.UnreadableRow)
        assertEquals(2L, (failure as WalletException.UnreadableRow).id)
    }

    @Test
    fun aNullRateOnANonBaseLegIsMissingRateEvenWhenARateIsSavedToday() = runTest {
        val entries = FakeEntryRepository(listOf(expense(usd("100"), rate = null)))

        assertEquals(CategoryBreakdownResult.MissingRate("USD"), breakdown(entries)(july).first())
    }

    @Test
    fun aBaseCurrencyLegWithNoStoredRateCountsAtFaceValue() = runTest {
        val entries = FakeEntryRepository(listOf(expense(sar("42.50"), rate = null)))

        assertEquals(sar("42.50"), breakdown(entries)(july).first().resolved().summary.expense)
    }

    @Test
    fun aNegativeExpenseLegReducesTheExpenseSide() = runTest {
        val entries = FakeEntryRepository()
        entries.seedRaw(rawEntry(1, "EXPENSE", categoryLeg(LegRole.MAIN, 7, sar("100"), "1")))
        entries.seedRaw(rawEntry(4, "EXPENSE", categoryLeg(LegRole.MAIN, 7, sar("-20"), "1")))

        assertEquals(sar("80"), breakdown(entries)(july).first().resolved().summary.expense)
    }

    @Test
    fun eachLegIsConvertedAtItsOwnStoredRate() = runTest {
        val entries = FakeEntryRepository()
        entries.seedRaw(rawEntry(1, "EXPENSE", categoryLeg(LegRole.MAIN, 7, usd("100"), "3.75")))
        entries.seedRaw(rawEntry(2, "EXPENSE", categoryLeg(LegRole.MAIN, 7, usd("100"), "3.80")))

        assertEquals(sar("755.00"), breakdown(entries)(july).first().resolved().summary.expense)
    }

    private suspend fun breakdownOf(entries: FakeEntryRepository) = breakdown(entries)(july).first().resolved()
}
