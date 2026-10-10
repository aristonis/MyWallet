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
import java.time.LocalDate

/**
 * A loan given is money out of an account and the same amount owed to me: one asset (cash) becomes
 * another (a receivable), so the owner is no poorer and nothing counts in Tracking. The debt is in
 * the account's currency.
 */
object LendRecipe : EntryRecipe<Transaction.Lend> {

    override val kind: String = "LEND"

    private val shape = LegShape(required = setOf(LegRole.ACCOUNT, LegRole.DEBT))

    /** Empty: a loan given has no category legs. */
    override val trackingRuleByRole: Map<LegRole, TrackingRule> = emptyMap()

    override fun toLegs(entry: Transaction.Lend): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note),
        legs = listOf(
            // Unary minus negates the BigDecimal, so the stored text keeps the amount's scale.
            Leg(LegRole.ACCOUNT, 0, LegTarget.Account(entry.accountId), -entry.amount),
            Leg(LegRole.DEBT, 0, LegTarget.Debt(entry.debtId, DebtSide.OWED_TO_ME), entry.amount),
        ),
    )

    override fun fromLegs(parts: EntryParts): Transaction.Lend = readParts(parts, kind, shape) { legs ->
        val account = legs.getValue(LegRole.ACCOUNT).single()
        val debt = legs.getValue(LegRole.DEBT).single()
        require(parts.header.pairRate == null) { "a loan given has no pair rate" }
        require(account.amount == -debt.amount) { "lent ${debt.amount} differs from money out ${account.amount}" }
        Transaction.Lend(
            id = parts.header.id,
            accountId = account.accountId(),
            debtId = debt.debtId(DebtSide.OWED_TO_ME),
            amount = debt.amount,
            date = parts.header.date,
            note = parts.header.note,
        )
    }

    override fun examples(): List<Transaction.Lend> = listOf(
        Transaction.Lend(
            id = 1, accountId = 1, debtId = 1, amount = Money.of("300.00", "USD"),
            date = EXAMPLE_DATE, note = "To Sami",
        ),
        Transaction.Lend(id = 2, accountId = 2, debtId = 2, amount = Money.of("75.500", "KWD"), date = EXAMPLE_DATE),
    )

    private val EXAMPLE_DATE: LocalDate = LocalDate.of(2026, 5, 3)
}
