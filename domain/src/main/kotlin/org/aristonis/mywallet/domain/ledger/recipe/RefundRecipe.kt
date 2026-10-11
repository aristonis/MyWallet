package org.aristonis.mywallet.domain.ledger.recipe

import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.EntryRecipe
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.TrackingRule
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Stores a refund as money returning to an account and an equal negative expense-category leg.
 * The negative category amount is what makes Tracking reduce the original expense without treating
 * the returned money as income.
 */
object RefundRecipe : EntryRecipe<Transaction.Refund> {

    override val kind: String = "REFUND"

    private val shape = LegShape(required = setOf(LegRole.ACCOUNT, LegRole.MAIN))
    override val trackingRuleByRole: Map<LegRole, TrackingRule> =
        mapOf(LegRole.MAIN to TrackingRule.REDUCES_EXPENSE)

    override fun toLegs(entry: Transaction.Refund): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note),
        legs = listOf(
            Leg(LegRole.ACCOUNT, 0, LegTarget.Account(entry.accountId), entry.amount),
            Leg(
                LegRole.MAIN, 0, LegTarget.Category(entry.categoryId, entry.subCategoryId),
                -entry.amount, entry.rateToBase,
            ),
        ),
    )

    override fun fromLegs(parts: EntryParts): Transaction.Refund = readParts(parts, kind, shape) { legs ->
        val account = legs.getValue(LegRole.ACCOUNT).single()
        val main = legs.getValue(LegRole.MAIN).single()
        require(parts.header.pairRate == null) { "a refund has no pair rate" }
        require(account.amount == -main.amount) {
            "refund ${account.amount} differs from reduced expense ${main.amount}"
        }
        val category = main.categoryTarget()
        Transaction.Refund(
            id = parts.header.id,
            accountId = account.accountId(),
            amount = account.amount,
            categoryId = category.id,
            subCategoryId = category.subId,
            date = parts.header.date,
            note = parts.header.note,
            rateToBase = main.rateToBase,
        )
    }

    override fun examples(): List<Transaction.Refund> = listOf(
        Transaction.Refund(
            id = 1, accountId = 1, amount = Money.of("42.10", "USD"), categoryId = 4,
            date = EXAMPLE_DATE, note = "Returned groceries", rateToBase = BigDecimal.ONE,
        ),
        Transaction.Refund(
            id = 2, accountId = 2, amount = Money.of("12.345", "KWD"), categoryId = 5, subCategoryId = 6,
            date = EXAMPLE_DATE, rateToBase = BigDecimal("3.25700"),
        ),
        Transaction.Refund(
            id = 3, accountId = 3, amount = Money.of("75000", "SYP"), categoryId = 4,
            date = EXAMPLE_DATE, note = "No rate saved yet",
        ),
    )

    private val EXAMPLE_DATE: LocalDate = LocalDate.of(2026, 10, 12)
}
