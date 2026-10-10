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
 * A borrow is money into an account and the same amount added to what I owe: an asset and a
 * liability grow together, so the owner is no richer and nothing counts in Tracking. The debt is in
 * the account's currency.
 */
object BorrowRecipe : EntryRecipe<Transaction.Borrow> {

    override val kind: String = "BORROW"

    override suspend fun prepareForSave(entry: Transaction.Borrow, context: EntrySaveContext): Transaction.Borrow =
        entry.copy(debtId = context.debtId(entry.debtId, DebtSide.I_OWE, entry.amount.currencyCode))

    private val shape = LegShape(required = setOf(LegRole.ACCOUNT, LegRole.DEBT))

    /** Empty: a borrow has no category legs. */
    override val trackingRuleByRole: Map<LegRole, TrackingRule> = emptyMap()

    override fun toLegs(entry: Transaction.Borrow): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note),
        legs = listOf(
            Leg(LegRole.ACCOUNT, 0, LegTarget.Account(entry.accountId), entry.amount),
            Leg(LegRole.DEBT, 0, LegTarget.Debt(entry.debtId, DebtSide.I_OWE), entry.amount),
        ),
    )

    override fun fromLegs(parts: EntryParts): Transaction.Borrow = readParts(parts, kind, shape) { legs ->
        val account = legs.getValue(LegRole.ACCOUNT).single()
        val debt = legs.getValue(LegRole.DEBT).single()
        require(parts.header.pairRate == null) { "a borrow has no pair rate" }
        require(account.amount == debt.amount) { "borrowed ${debt.amount} differs from money in ${account.amount}" }
        Transaction.Borrow(
            id = parts.header.id,
            accountId = account.accountId(),
            debtId = debt.debtId(DebtSide.I_OWE),
            amount = debt.amount,
            date = parts.header.date,
            note = parts.header.note,
        )
    }

    override fun examples(): List<Transaction.Borrow> = listOf(
        Transaction.Borrow(
            id = 1, accountId = 1, debtId = 1, amount = Money.of("5000.00", "USD"),
            date = EXAMPLE_DATE, note = "Bank loan",
        ),
        Transaction.Borrow(id = 2, accountId = 3, debtId = 2, amount = Money.of("250000", "SYP"), date = EXAMPLE_DATE),
    )

    private val EXAMPLE_DATE: LocalDate = LocalDate.of(2026, 5, 2)
}
