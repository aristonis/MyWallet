package org.aristonis.mywallet.domain.ledger.recipe

import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.EntryRecipe
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.TrackingRule
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Buying now and paying later: what I owe grows and the expense counts today, on the day of the
 * purchase, not on the day it is paid. No account moves; the later repayment only settles the debt.
 */
object CreditPurchaseRecipe : EntryRecipe<Transaction.CreditPurchase> {

    override val kind: String = "CREDIT_PURCHASE"

    private val shape = LegShape(required = setOf(LegRole.DEBT, LegRole.MAIN))
    override val trackingRuleByRole: Map<LegRole, TrackingRule> = mapOf(LegRole.MAIN to TrackingRule.EXPENSE)

    override fun toLegs(entry: Transaction.CreditPurchase): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note),
        legs = listOf(
            Leg(LegRole.DEBT, 0, LegTarget.Debt(entry.debtId, DebtSide.I_OWE), entry.amount),
            Leg(
                LegRole.MAIN, 0, LegTarget.Category(entry.categoryId, entry.subCategoryId),
                entry.amount, entry.rateToBase,
            ),
        ),
    )

    override fun fromLegs(parts: EntryParts): Transaction.CreditPurchase = readParts(parts, kind, shape) { legs ->
        val debt = legs.getValue(LegRole.DEBT).single()
        val main = legs.getValue(LegRole.MAIN).single()
        require(parts.header.pairRate == null) { "a credit purchase has no pair rate" }
        require(debt.amount == main.amount) { "expense ${main.amount} differs from the debt growing ${debt.amount}" }
        val category = main.categoryTarget()
        Transaction.CreditPurchase(
            id = parts.header.id,
            debtId = debt.debtId(DebtSide.I_OWE),
            amount = main.amount,
            categoryId = category.id,
            subCategoryId = category.subId,
            date = parts.header.date,
            note = parts.header.note,
            rateToBase = main.rateToBase,
        )
    }

    override fun examples(): List<Transaction.CreditPurchase> = listOf(
        Transaction.CreditPurchase(
            id = 1, debtId = 1, amount = Money.of("120.50", "USD"), categoryId = 4, subCategoryId = 5,
            date = EXAMPLE_DATE, note = "Laptop bag", rateToBase = BigDecimal.ONE,
        ),
        Transaction.CreditPurchase(
            id = 2, debtId = 2, amount = Money.of("60.00", "EUR"), categoryId = 4,
            date = EXAMPLE_DATE, rateToBase = BigDecimal("1.08370"),
        ),
        Transaction.CreditPurchase(
            id = 3, debtId = 3, amount = Money.of("40000", "SYP"), categoryId = 4,
            date = EXAMPLE_DATE, note = "No rate saved yet",
        ),
    )

    private val EXAMPLE_DATE: LocalDate = LocalDate.of(2026, 5, 4)
}
