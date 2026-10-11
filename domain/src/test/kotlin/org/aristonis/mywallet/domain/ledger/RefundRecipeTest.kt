package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.recipe.RefundRecipe
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class RefundRecipeTest {

    @Test
    fun refundAddsMoneyToTheAccountAndReducesItsExpenseCategory() {
        val refund = Transaction.Refund(
            id = 12,
            accountId = 3,
            amount = Money.of("200", "USD"),
            categoryId = 8,
            subCategoryId = 9,
            date = LocalDate.of(2026, 10, 12),
            note = "Returned phone case",
            rateToBase = BigDecimal.ONE,
        )

        val parts = refund.toParts()

        assertEquals(RefundRecipe, refund.recipe())
        assertEquals(EntryHeader(12, "REFUND", LocalDate.of(2026, 10, 12), "Returned phone case"), parts.header)
        assertEquals(
            listOf(
                Leg(LegRole.ACCOUNT, 0, LegTarget.Account(3), Money.of("200", "USD")),
                Leg(LegRole.MAIN, 0, LegTarget.Category(8, 9), Money.of("-200", "USD"), BigDecimal.ONE),
            ),
            parts.legs,
        )
        assertEquals(TrackingRule.REDUCES_EXPENSE, refund.recipe().trackingRules(refund).getValue(LegRole.MAIN))
        assertEquals(refund, refund.recipe().fromLegs(parts))
    }

    // fromLegs failure paths: a stored row that is not a refund is reported as unreadable, never half-read.

    @Test
    fun legsWhoseAmountsDifferByACentAreUnreadable() {
        assertUnreadable(parts(accountAmount = "200", mainAmount = "-199.99"))
    }

    @Test
    fun aMainLegThatAddsInsteadOfReducingIsUnreadable() {
        assertUnreadable(parts(accountAmount = "200", mainAmount = "200"))
    }

    @Test
    fun aRefundRowWithAPairRateIsUnreadable() {
        assertUnreadable(parts(pairRate = BigDecimal("1.5")))
    }

    @Test
    fun aRefundRowMissingItsMainLegIsUnreadable() {
        val full = parts()

        assertUnreadable(full.copy(legs = full.legs.filter { it.role != LegRole.MAIN }))
    }

    @Test
    fun aRefundRowWhoseMainLegIsOnAnAccountIsUnreadable() {
        val full = parts()
        val onAccount = full.legs.map { if (it.role == LegRole.MAIN) it.copy(target = LegTarget.Account(5)) else it }

        assertUnreadable(full.copy(legs = onAccount))
    }

    @Test
    fun aRowWithAnotherKindInTheHeaderIsUnreadable() {
        val full = parts()

        assertUnreadable(full.copy(header = full.header.copy(kind = "EXPENSE")))
    }

    @Test
    fun aRefundOfZeroOrLessCannotBeBuilt() {
        assertThrows(IllegalArgumentException::class.java) { refundOf("0") }
        assertThrows(IllegalArgumentException::class.java) { refundOf("-1") }
    }

    private fun refundOf(amount: String) = Transaction.Refund(
        accountId = 3, amount = Money.of(amount, "USD"), categoryId = 8, date = LocalDate.of(2026, 10, 12),
    )

    private fun parts(accountAmount: String = "200", mainAmount: String = "-200", pairRate: BigDecimal? = null) =
        EntryParts(
            header = EntryHeader(12, "REFUND", LocalDate.of(2026, 10, 12), null, pairRate),
            legs = listOf(
                Leg(LegRole.ACCOUNT, 0, LegTarget.Account(3), Money.of(accountAmount, "USD")),
                Leg(LegRole.MAIN, 0, LegTarget.Category(8, null), Money.of(mainAmount, "USD"), BigDecimal.ONE),
            ),
        )

    private fun assertUnreadable(parts: EntryParts) {
        val error = assertThrows(WalletException.UnreadableRow::class.java) { RefundRecipe.fromLegs(parts) }
        assertEquals(12L, error.id)
        assertEquals(parts.header.kind, error.kind)
    }
}
