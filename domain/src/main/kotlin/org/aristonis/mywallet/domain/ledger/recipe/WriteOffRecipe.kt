package org.aristonis.mywallet.domain.ledger.recipe

import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.EntryRecipe
import org.aristonis.mywallet.domain.ledger.EntrySaveContext
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.TrackingRule
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Writing off the rest of a debt settles it without money. When what was owed to me will never come,
 * the receivable is gone and that is a loss (an expense); when what I owe is let go, the liability is
 * gone and that is forgiven (income). Either way the debt shrinks by what the category records.
 */
object WriteOffRecipe : EntryRecipe<Transaction.WriteOff> {

    override val kind: String = "WRITE_OFF"

    override suspend fun prepareForSave(entry: Transaction.WriteOff, context: EntrySaveContext): Transaction.WriteOff {
        val debtId = context.debtId(entry.debtId, entry.side, entry.amount.currencyCode)
        val role = when (entry.side) {
            DebtSide.OWED_TO_ME -> CategoryRole.LOST_DEBT
            DebtSide.I_OWE -> CategoryRole.DEBT_FORGIVEN
        }
        val categoryId = context.roleCategories.categoryFor(role, entry.categoryId.takeUnless { it == 0L })
        return entry.copy(debtId = debtId, categoryId = categoryId)
    }

    // Exactly one of LOSS and FORGIVEN, the one the debt's side gives; the reader checks which.
    private val shape = LegShape(required = setOf(LegRole.DEBT), optional = setOf(LegRole.LOSS, LegRole.FORGIVEN))

    override val trackingRuleByRole: Map<LegRole, TrackingRule> = mapOf(
        LegRole.LOSS to TrackingRule.EXPENSE,
        LegRole.FORGIVEN to TrackingRule.INCOME,
    )

    /** Only the role this write-off writes, with the rule the kind declares for it. */
    override fun trackingRules(entry: Transaction.WriteOff): Map<LegRole, TrackingRule> =
        categoryRole(entry.side).let { role -> mapOf(role to trackingRuleByRole.getValue(role)) }

    override fun toLegs(entry: Transaction.WriteOff): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note),
        legs = listOf(
            // Unary minus negates the BigDecimal, so the stored text keeps the amount's scale.
            Leg(LegRole.DEBT, 0, LegTarget.Debt(entry.debtId, entry.side), -entry.amount),
            Leg(
                categoryRole(entry.side), 0, LegTarget.Category(entry.categoryId, entry.subCategoryId),
                entry.amount, entry.rateToBase,
            ),
        ),
    )

    override fun fromLegs(parts: EntryParts): Transaction.WriteOff = readParts(parts, kind, shape) { legs ->
        val debtLeg = legs.getValue(LegRole.DEBT).single()
        val debt = debtLeg.debtTarget()
        val role = categoryRole(debt.side)
        require(legs.keys == setOf(LegRole.DEBT, role)) { "a ${debt.side} write-off has roles ${legs.keys}" }
        val written = legs.getValue(role).single()
        require(parts.header.pairRate == null) { "a write-off has no pair rate" }
        require(debtLeg.amount == -written.amount) { "written off ${written.amount} differs from the debt ${debtLeg.amount}" }
        val category = written.categoryTarget()
        Transaction.WriteOff(
            id = parts.header.id,
            side = debt.side,
            debtId = debt.id,
            amount = written.amount,
            categoryId = category.id,
            subCategoryId = category.subId,
            rateToBase = written.rateToBase,
            date = parts.header.date,
            note = parts.header.note,
        )
    }

    private fun categoryRole(side: DebtSide): LegRole = when (side) {
        DebtSide.OWED_TO_ME -> LegRole.LOSS
        DebtSide.I_OWE -> LegRole.FORGIVEN
    }

    override fun examples(): List<Transaction.WriteOff> = listOf(
        Transaction.WriteOff(
            id = 1, side = DebtSide.OWED_TO_ME, debtId = 1, amount = Money.of("75.00", "USD"), categoryId = 20,
            rateToBase = BigDecimal.ONE, date = EXAMPLE_DATE, note = "Never paid back",
        ),
        Transaction.WriteOff(
            id = 2, side = DebtSide.I_OWE, debtId = 2, amount = Money.of("60.00", "EUR"), categoryId = 21,
            subCategoryId = 22, rateToBase = BigDecimal("1.08370"), date = EXAMPLE_DATE,
        ),
        Transaction.WriteOff(
            id = 3, side = DebtSide.I_OWE, debtId = 3, amount = Money.of("15000", "SYP"), categoryId = 21,
            date = EXAMPLE_DATE, note = "No rate saved yet",
        ),
    )

    private val EXAMPLE_DATE: LocalDate = LocalDate.of(2026, 6, 30)
}
