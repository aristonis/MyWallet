package org.aristonis.mywallet.domain.ledger.recipe

import org.aristonis.mywallet.domain.error.WalletException
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
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A transfer moves money between two of the owner's own accounts. An optional fee is an expense
 * category leg in the sending currency; the destination is priced from the remainder. The rate
 * between the two currencies lives on the header, because it belongs to the pair, not to one leg.
 */
object TransferRecipe : EntryRecipe<Transaction.Transfer> {

    override val kind: String = "TRANSFER"

    override suspend fun prepareForSave(entry: Transaction.Transfer, context: EntrySaveContext): Transaction.Transfer {
        val destCurrency = context.accounts.findById(entry.destAccountId)?.currencyCode
            ?: throw WalletException.AccountNotFound(entry.destAccountId)
        val normalized = entry.copy(
            fee = entry.fee?.takeUnless { it.isZero },
            feeCategoryId = context.feeCategory(entry.fee, entry.feeCategoryId),
            feeRateToBase = entry.feeRateToBase.takeIf { entry.fee?.isPositive == true },
        )
        val old = context.old
        return if (old == null) normalized.pricedAsNew(destCurrency, context.book)
        else normalized.pricedAsEditOf(old, destCurrency, context.snapshot)
    }

    private val shape = LegShape(required = setOf(LegRole.SOURCE, LegRole.DEST), optional = setOf(LegRole.FEE))

    override fun toLegs(entry: Transaction.Transfer): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note, pairRate = entry.rateUsed),
        legs = listOf(
            // Unary minus negates the BigDecimal, so the stored text keeps the amount's scale.
            Leg(LegRole.SOURCE, 0, LegTarget.Account(entry.sourceAccountId), -entry.sourceAmount),
            Leg(LegRole.DEST, 0, LegTarget.Account(entry.destAccountId), entry.destAmount),
        ) + entry.fee?.let { fee ->
            listOf(
                Leg(
                    LegRole.FEE, 0, LegTarget.Category(requireNotNull(entry.feeCategoryId), null), fee,
                    entry.feeRateToBase,
                ),
            )
        }.orEmpty(),
    )

    /**
     * Reads a stored transfer by its structure only: signs, a net above zero, a pair rate above zero,
     * rate 1 and an equal amount for the same currency, and a consistent fee leg. The destination of
     * a cross-currency transfer is never re-priced here. Builds up to 1.1.1 priced it through the base
     * at full precision and stored the pair rate at 12 places, and never limited typed amounts to a
     * currency's decimals, so a re-check on read would make shipped rows unreadable. The save prices.
     */
    override fun fromLegs(parts: EntryParts): Transaction.Transfer = readParts(parts, kind, shape) { legs ->
        val source = legs.getValue(LegRole.SOURCE).single()
        val dest = legs.getValue(LegRole.DEST).single()
        val fee = legs[LegRole.FEE]?.singleOrNull()
        val pairRate = requireReadablePricing(parts, source, dest, fee)
        val feeCategory = fee?.categoryTarget()
        require(feeCategory?.subId == null) { "transfer fee cannot have a sub-category" }
        Transaction.Transfer(
            id = parts.header.id,
            sourceAccountId = source.accountId(),
            destAccountId = dest.accountId(),
            sourceAmount = -source.amount,
            destAmount = dest.amount,
            rateUsed = pairRate,
            date = parts.header.date,
            note = parts.header.note,
            fee = fee?.amount,
            feeCategoryId = feeCategory?.id,
            feeRateToBase = fee?.rateToBase,
        )
    }

    /** The structural checks of a stored transfer; returns its pair rate. */
    private fun requireReadablePricing(parts: EntryParts, source: Leg, dest: Leg, fee: Leg?): BigDecimal {
        require(source.amount.isNegative) { "transfer source ${source.amount} is not money out" }
        require(dest.amount.isPositive) { "transfer destination ${dest.amount} is not money in" }
        val pairRate = requireNotNull(parts.header.pairRate) { "a transfer needs its pair rate" }
        require(pairRate.signum() > 0) { "transfer pair rate $pairRate is not above zero" }
        require(fee == null || fee.amount.isPositive) { "transfer fee is not positive" }
        val net = -source.amount - (fee?.amount ?: Money.zero(source.amount.currencyCode))
        require(net.isPositive) { "transfer fee leaves no amount to send" }
        if (net.currencyCode == dest.amount.currencyCode) {
            require(pairRate.compareTo(BigDecimal.ONE) == 0) { "same-currency transfer rate must be one" }
            require(dest.amount == net) { "transfer destination ${dest.amount} differs from net source $net" }
        }
        return pairRate
    }

    /** Empty: a transfer has no category legs, and account legs never count in Tracking. */
    override val trackingRuleByRole: Map<LegRole, TrackingRule> = mapOf(LegRole.FEE to TrackingRule.EXPENSE)

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
