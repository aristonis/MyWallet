package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Income, expense and transfer must come out as exactly the legs the database upgrade writes for the
 * rows that already exist, or an upgraded row and a newly saved one would read differently.
 */
class LegacyKindRecipesTest {

    private val day = LocalDate.of(2026, 3, 14)

    @Test
    fun anIncomeIsMoneyIntoTheAccountAndIncomeInItsCategory() {
        val income = Transaction.Income(
            id = 7, accountId = 2, amount = Money.of("1.50", "USD"), categoryId = 4, subCategoryId = 9,
            date = day, note = "tip", rateToBase = BigDecimal("3.75"),
        )

        val parts = income.toParts()

        assertEquals(EntryHeader(7, "INCOME", day, "tip", pairRate = null), parts.header)
        assertEquals(
            listOf(
                Leg(LegRole.ACCOUNT, 0, LegTarget.Account(2), Money.of("1.50", "USD")),
                Leg(LegRole.MAIN, 0, LegTarget.Category(4, 9), Money.of("1.50", "USD"), BigDecimal("3.75")),
            ),
            parts.legs,
        )
        assertEquals("1.50", parts.legs[0].amount.amount.toPlainString())
        assertEquals(TrackingRule.INCOME, income.recipe().trackingRules(income).getValue(LegRole.MAIN))
    }

    @Test
    fun anExpenseIsMoneyOutOfTheAccountAndExpenseInItsCategory() {
        val expense = Transaction.Expense(
            id = 8, accountId = 2, amount = Money.of("12.345", "KWD"), categoryId = 5, date = day, rateToBase = null,
        )

        val parts = expense.toParts()

        assertEquals(LegRole.ACCOUNT, parts.legs[0].role)
        assertEquals("-12.345", parts.legs[0].amount.amount.toPlainString())
        assertEquals(Leg(LegRole.MAIN, 0, LegTarget.Category(5, null), Money.of("12.345", "KWD"), null), parts.legs[1])
        assertNull(parts.legs[1].rateToBase)
        assertEquals(TrackingRule.EXPENSE, expense.recipe().trackingRules(expense).getValue(LegRole.MAIN))
    }

    @Test
    fun aTransferMovesMoneyBetweenTwoAccountsAndCountsNowhere() {
        val transfer = Transaction.Transfer(
            id = 9, sourceAccountId = 2, destAccountId = 3,
            sourceAmount = Money.of("100.00", "USD"), destAmount = Money.of("92.59", "EUR"),
            rateUsed = BigDecimal("0.9259"), date = day, note = null,
        )

        val parts = transfer.toParts()

        assertEquals(BigDecimal("0.9259"), parts.header.pairRate)
        assertEquals(
            listOf(
                Leg(LegRole.SOURCE, 0, LegTarget.Account(2), Money.of("-100.00", "USD")),
                Leg(LegRole.DEST, 0, LegTarget.Account(3), Money.of("92.59", "EUR")),
            ),
            parts.legs,
        )
        assertEquals("-100.00", parts.legs[0].amount.amount.toPlainString())
        assertEquals(transfer, transfer.recipe().fromLegs(parts))
    }

    @Test
    fun theStoredRateRoundTripsAndKeepsItsScale() {
        val income = Transaction.Income(
            id = 1, accountId = 1, amount = Money.of("10", "EUR"), categoryId = 1,
            date = day, rateToBase = BigDecimal("1.08370"),
        )
        val back = income.recipe().fromLegs(income.toParts()) as Transaction.Income
        assertEquals("1.08370", back.rateToBase!!.toPlainString())
    }
}
