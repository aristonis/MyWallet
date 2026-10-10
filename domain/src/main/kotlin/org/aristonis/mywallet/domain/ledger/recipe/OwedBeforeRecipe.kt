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
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import java.time.LocalDate

/**
 * A debt that existed before the app is an opening balance: only the debt leg, on either side. No
 * money moved and nothing was earned or spent inside the app, so it counts nowhere in Tracking and is
 * the one kind exempt from the balance check.
 */
object OwedBeforeRecipe : EntryRecipe<Transaction.OwedBefore> {

    override val kind: String = "OWED_BEFORE"

    override suspend fun prepareForSave(entry: Transaction.OwedBefore, context: EntrySaveContext): Transaction.OwedBefore =
        entry.copy(debtId = context.debtId(entry.debtId, entry.side, entry.amount.currencyCode))

    private val shape = LegShape(required = setOf(LegRole.DEBT))

    /** Empty: an opening balance has no category legs. */
    override val trackingRuleByRole: Map<LegRole, TrackingRule> = emptyMap()

    override val opensBalances: Boolean = true

    override fun toLegs(entry: Transaction.OwedBefore): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note),
        legs = listOf(Leg(LegRole.DEBT, 0, LegTarget.Debt(entry.debtId, entry.side), entry.amount)),
    )

    override fun fromLegs(parts: EntryParts): Transaction.OwedBefore = readParts(parts, kind, shape) { legs ->
        val leg = legs.getValue(LegRole.DEBT).single()
        require(parts.header.pairRate == null) { "an opening balance has no pair rate" }
        // Either side fits this kind; the debt row's own side, filled on read, is the entry's side.
        val debt = leg.debtTarget()
        Transaction.OwedBefore(
            id = parts.header.id,
            debtId = debt.id,
            side = debt.side,
            amount = leg.amount,
            date = parts.header.date,
            note = parts.header.note,
        )
    }

    override fun examples(): List<Transaction.OwedBefore> = listOf(
        Transaction.OwedBefore(
            id = 1, debtId = 1, side = DebtSide.I_OWE, amount = Money.of("900.00", "USD"),
            date = EXAMPLE_DATE, note = "Before the app",
        ),
        Transaction.OwedBefore(
            id = 2, debtId = 2, side = DebtSide.OWED_TO_ME, amount = Money.of("120.50", "EUR"), date = EXAMPLE_DATE,
        ),
    )

    private val EXAMPLE_DATE: LocalDate = LocalDate.of(2026, 1, 1)
}
