package org.aristonis.mywallet.domain.ledger.recipe

import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.EntryRecipe
import org.aristonis.mywallet.domain.ledger.EntrySaveContext
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.TrackingRule
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.error.WalletException
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A transfer moves money between two of the owner's own accounts: one asset down, another up. No
 * income or expense happens, so it has no category legs and counts nowhere in Tracking. The rate
 * between the two currencies lives on the header, because it belongs to the pair, not to one leg.
 */
object TransferRecipe : EntryRecipe<Transaction.Transfer> {

    override val kind: String = "TRANSFER"

    override suspend fun prepareForSave(entry: Transaction.Transfer, context: EntrySaveContext): Transaction.Transfer {
        val destCurrency = context.accounts.findById(entry.destAccountId)?.currencyCode
            ?: throw WalletException.AccountNotFound(entry.destAccountId)
        val old = context.old
        return if (old == null) entry.pricedAsNew(destCurrency, context.book)
        else entry.pricedAsEditOf(old, destCurrency, context.snapshot)
    }

    private val shape = LegShape(required = setOf(LegRole.SOURCE, LegRole.DEST))

    override fun toLegs(entry: Transaction.Transfer): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note, pairRate = entry.rateUsed),
        legs = listOf(
            // Unary minus negates the BigDecimal, so the stored text keeps the amount's scale.
            Leg(LegRole.SOURCE, 0, LegTarget.Account(entry.sourceAccountId), -entry.sourceAmount),
            Leg(LegRole.DEST, 0, LegTarget.Account(entry.destAccountId), entry.destAmount),
        ),
    )

    override fun fromLegs(parts: EntryParts): Transaction.Transfer = readParts(parts, kind, shape) { legs ->
        val source = legs.getValue(LegRole.SOURCE).single()
        val dest = legs.getValue(LegRole.DEST).single()
        require(source.amount.isNegative) { "transfer source ${source.amount} is not money out" }
        require(dest.amount.isPositive) { "transfer destination ${dest.amount} is not money in" }
        val pairRate = requireNotNull(parts.header.pairRate) { "a transfer needs its pair rate" }
        require(pairRate.signum() > 0) { "transfer pair rate $pairRate is not above zero" }
        Transaction.Transfer(
            id = parts.header.id,
            sourceAccountId = source.accountId(),
            destAccountId = dest.accountId(),
            sourceAmount = -source.amount,
            destAmount = dest.amount,
            rateUsed = pairRate,
            date = parts.header.date,
            note = parts.header.note,
        )
    }

    /** Empty: a transfer has no category legs, and account legs never count in Tracking. */
    override val trackingRuleByRole: Map<LegRole, TrackingRule> = emptyMap()

    override fun examples(): List<Transaction.Transfer> = listOf(
        Transaction.Transfer(
            id = 1, sourceAccountId = 1, destAccountId = 2,
            sourceAmount = Money.of("50.00", "USD"), destAmount = Money.of("50.00", "USD"),
            rateUsed = BigDecimal.ONE, date = EXAMPLE_DATE, note = "To savings",
        ),
        Transaction.Transfer(
            id = 2, sourceAccountId = 1, destAccountId = 3,
            sourceAmount = Money.of("100.00", "USD"), destAmount = Money.of("92.59", "EUR"),
            rateUsed = BigDecimal("0.9259"), date = EXAMPLE_DATE,
        ),
    )

    private val EXAMPLE_DATE: LocalDate = LocalDate.of(2026, 3, 1)
}
