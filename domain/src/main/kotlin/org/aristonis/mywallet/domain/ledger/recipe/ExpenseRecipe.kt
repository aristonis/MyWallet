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
 * An expense is money out of an account and the same amount as expense in its category: the asset
 * goes down by exactly what the expense category records, so the books balance.
 */
object ExpenseRecipe : EntryRecipe<Transaction.Expense> {

    override val kind: String = "EXPENSE"

    private val shape = LegShape(required = setOf(LegRole.ACCOUNT, LegRole.MAIN))
    private val rules = mapOf(LegRole.MAIN to TrackingRule.EXPENSE)

    override fun toLegs(entry: Transaction.Expense): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note),
        legs = listOf(
            // Unary minus negates the BigDecimal, so the stored text keeps the amount's scale.
            Leg(LegRole.ACCOUNT, 0, LegTarget.Account(entry.accountId), -entry.amount),
            Leg(
                LegRole.MAIN, 0, LegTarget.Category(entry.categoryId, entry.subCategoryId),
                entry.amount, entry.rateToBase,
            ),
        ),
    )

    override fun fromLegs(parts: EntryParts): Transaction.Expense = readParts(parts, kind, shape) { legs ->
        val account = legs.getValue(LegRole.ACCOUNT).single()
        val main = legs.getValue(LegRole.MAIN).single()
        require(parts.header.pairRate == null) { "an expense has no pair rate" }
        require(account.amount == -main.amount) { "expense ${main.amount} differs from money out ${account.amount}" }
        val category = main.categoryTarget()
        Transaction.Expense(
            id = parts.header.id,
            accountId = account.accountId(),
            amount = main.amount,
            categoryId = category.id,
            subCategoryId = category.subId,
            date = parts.header.date,
            note = parts.header.note,
            rateToBase = main.rateToBase,
        )
    }

    override fun trackingRules(entry: Transaction.Expense): Map<LegRole, TrackingRule> = rules

    override fun examples(): List<Transaction.Expense> = listOf(
        Transaction.Expense(
            id = 1, accountId = 1, amount = Money.of("42.10", "USD"), categoryId = 4,
            date = EXAMPLE_DATE, note = "Groceries", rateToBase = BigDecimal.ONE,
        ),
        Transaction.Expense(
            id = 2, accountId = 2, amount = Money.of("12.345", "KWD"), categoryId = 5, subCategoryId = 6,
            date = EXAMPLE_DATE, rateToBase = BigDecimal("3.25700"),
        ),
        Transaction.Expense(
            id = 3, accountId = 3, amount = Money.of("75000", "SYP"), categoryId = 4,
            date = EXAMPLE_DATE, note = "No rate saved yet",
        ),
    )

    private val EXAMPLE_DATE: LocalDate = LocalDate.of(2026, 2, 14)
}
