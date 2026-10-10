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
 * Income earned now and paid later: what is owed to me grows and the income counts today, when it was
 * earned, not when the money arrives. No account moves; collecting it later only settles the debt.
 */
object EarnedUnpaidRecipe : EntryRecipe<Transaction.EarnedUnpaid> {

    override val kind: String = "EARNED_UNPAID"

    private val shape = LegShape(required = setOf(LegRole.DEBT, LegRole.MAIN))
    override val trackingRuleByRole: Map<LegRole, TrackingRule> = mapOf(LegRole.MAIN to TrackingRule.INCOME)

    override fun toLegs(entry: Transaction.EarnedUnpaid): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note),
        legs = listOf(
            Leg(LegRole.DEBT, 0, LegTarget.Debt(entry.debtId, DebtSide.OWED_TO_ME), entry.amount),
            Leg(
                LegRole.MAIN, 0, LegTarget.Category(entry.categoryId, entry.subCategoryId),
                entry.amount, entry.rateToBase,
            ),
        ),
    )

    override fun fromLegs(parts: EntryParts): Transaction.EarnedUnpaid = readParts(parts, kind, shape) { legs ->
        val debt = legs.getValue(LegRole.DEBT).single()
        val main = legs.getValue(LegRole.MAIN).single()
        require(parts.header.pairRate == null) { "earned-unpaid income has no pair rate" }
        require(debt.amount == main.amount) { "income ${main.amount} differs from the debt growing ${debt.amount}" }
        val category = main.categoryTarget()
        Transaction.EarnedUnpaid(
            id = parts.header.id,
            debtId = debt.debtId(DebtSide.OWED_TO_ME),
            amount = main.amount,
            categoryId = category.id,
            subCategoryId = category.subId,
            date = parts.header.date,
            note = parts.header.note,
            rateToBase = main.rateToBase,
        )
    }

    override fun examples(): List<Transaction.EarnedUnpaid> = listOf(
        Transaction.EarnedUnpaid(
            id = 1, debtId = 1, amount = Money.of("800.00", "USD"), categoryId = 1,
            date = EXAMPLE_DATE, note = "Invoice 12", rateToBase = BigDecimal.ONE,
        ),
        Transaction.EarnedUnpaid(
            id = 2, debtId = 2, amount = Money.of("150.00", "EUR"), categoryId = 2, subCategoryId = 3,
            date = EXAMPLE_DATE, rateToBase = BigDecimal("1.08370"),
        ),
        Transaction.EarnedUnpaid(
            id = 3, debtId = 3, amount = Money.of("90000", "SYP"), categoryId = 1,
            date = EXAMPLE_DATE, note = "No rate saved yet",
        ),
    )

    private val EXAMPLE_DATE: LocalDate = LocalDate.of(2026, 5, 5)
}
