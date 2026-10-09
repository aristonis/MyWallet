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
 * An income is money into an account and the same amount as income in its category. In accounting
 * terms the account (an asset) is debited and the income category credited, so the books balance.
 */
object IncomeRecipe : EntryRecipe<Transaction.Income> {

    override val kind: String = "INCOME"

    private val shape = LegShape(required = setOf(LegRole.ACCOUNT, LegRole.MAIN))
    override val trackingRuleByRole: Map<LegRole, TrackingRule> = mapOf(LegRole.MAIN to TrackingRule.INCOME)

    override fun toLegs(entry: Transaction.Income): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note),
        legs = listOf(
            Leg(LegRole.ACCOUNT, 0, LegTarget.Account(entry.accountId), entry.amount),
            Leg(
                LegRole.MAIN, 0, LegTarget.Category(entry.categoryId, entry.subCategoryId),
                entry.amount, entry.rateToBase,
            ),
        ),
    )

    override fun fromLegs(parts: EntryParts): Transaction.Income = readParts(parts, kind, shape) { legs ->
        val account = legs.getValue(LegRole.ACCOUNT).single()
        val main = legs.getValue(LegRole.MAIN).single()
        require(parts.header.pairRate == null) { "an income has no pair rate" }
        require(main.amount == account.amount) { "income ${main.amount} differs from money in ${account.amount}" }
        val category = main.categoryTarget()
        Transaction.Income(
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

    override fun examples(): List<Transaction.Income> = listOf(
        Transaction.Income(
            id = 1, accountId = 1, amount = Money.of("2500.00", "USD"), categoryId = 1,
            date = EXAMPLE_DATE, note = "Salary", rateToBase = BigDecimal.ONE,
        ),
        Transaction.Income(
            id = 2, accountId = 2, amount = Money.of("120.50", "EUR"), categoryId = 2, subCategoryId = 3,
            date = EXAMPLE_DATE, rateToBase = BigDecimal("1.08370"),
        ),
        Transaction.Income(
            id = 3, accountId = 3, amount = Money.of("15000", "SYP"), categoryId = 1,
            date = EXAMPLE_DATE, note = "No rate saved yet",
        ),
    )

    private val EXAMPLE_DATE: LocalDate = LocalDate.of(2026, 1, 31)
}
