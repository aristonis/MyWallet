package org.aristonis.mywallet.domain.ledger.recipe

import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.EntryRecipe
import org.aristonis.mywallet.domain.ledger.EntrySaveContext
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.TrackingRule
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A repayment settles part of a debt with money. Paying what I owe takes money out of an account;
 * collecting what is owed to me brings it in. Only the principal shrinks the debt (the liability or
 * receivable). Interest is the price of the loan: an expense when I pay it, income when I collect it,
 * booked in the account's currency.
 *
 * In one currency the money moved is exactly principal plus interest. Across currencies the rate
 * between the account and the debt sits on the header, and the principal is kept as saved: paying a
 * debt off in full is priced from the debt side (the debt drops by exactly what remained), so the
 * account amount and the principal need not convert back to each other at that rate.
 */
object RepaymentRecipe : EntryRecipe<Transaction.Repayment> {

    override val kind: String = "REPAYMENT"

    override suspend fun prepareForSave(entry: Transaction.Repayment, context: EntrySaveContext): Transaction.Repayment {
        val debtId = context.debtId(entry.debtId, entry.side, entry.principal.currencyCode)
        val role = when (entry.side) {
            DebtSide.I_OWE -> CategoryRole.INTEREST_OUT
            DebtSide.OWED_TO_ME -> CategoryRole.INTEREST_IN
        }
        val categoryId = if (entry.interest == null) entry.interestCategoryId else {
            context.roleCategories.categoryFor(role, entry.interestCategoryId)
        }
        // Both amounts and the pair rate are historical facts, including a rounded full payoff.
        return entry.copy(debtId = debtId, interestCategoryId = categoryId)
    }

    private val shape = LegShape(
        required = setOf(LegRole.ACCOUNT, LegRole.DEBT),
        optional = setOf(LegRole.INTEREST_PAID, LegRole.INTEREST_RECEIVED),
    )

    override val trackingRuleByRole: Map<LegRole, TrackingRule> = mapOf(
        LegRole.INTEREST_PAID to TrackingRule.EXPENSE,
        LegRole.INTEREST_RECEIVED to TrackingRule.INCOME,
    )

    /** Only the interest role this repayment writes, if any, with the rule the kind declares for it. */
    override fun trackingRules(entry: Transaction.Repayment): Map<LegRole, TrackingRule> {
        if (entry.interest == null) return emptyMap()
        val role = interestRole(entry.side)
        return mapOf(role to trackingRuleByRole.getValue(role))
    }

    /**
     * @throws IllegalArgumentException when the entry has interest but no interest category yet; the
     *   save picks one before the entry is stored.
     */
    override fun toLegs(entry: Transaction.Repayment): EntryParts {
        val accountSigned = when (entry.side) {
            DebtSide.I_OWE -> -entry.accountAmount
            DebtSide.OWED_TO_ME -> entry.accountAmount
        }
        val legs = listOf(
            Leg(LegRole.ACCOUNT, 0, LegTarget.Account(entry.accountId), accountSigned),
            // Written even when zero (interest only), so every repayment names the debt it belongs to.
            Leg(LegRole.DEBT, 0, LegTarget.Debt(entry.debtId, entry.side), -entry.principal),
        ) + listOfNotNull(entry.interest?.let { interest -> interestLeg(entry, interest) })
        return EntryParts(
            header = EntryHeader(entry.id, kind, entry.date, entry.note, pairRate = entry.pairRate),
            legs = legs,
        )
    }

    private fun interestLeg(entry: Transaction.Repayment, interest: Money): Leg {
        val categoryId = requireNotNull(entry.interestCategoryId) { "repayment interest has no category" }
        return Leg(
            interestRole(entry.side), 0, LegTarget.Category(categoryId, entry.interestSubCategoryId),
            interest, entry.interestRateToBase,
        )
    }

    override fun fromLegs(parts: EntryParts): Transaction.Repayment = readParts(parts, kind, shape) { legs ->
        val account = legs.getValue(LegRole.ACCOUNT).single()
        val debtLeg = legs.getValue(LegRole.DEBT).single()
        val debt = debtLeg.debtTarget()
        val accountAmount = when (debt.side) {
            DebtSide.I_OWE -> -account.amount
            DebtSide.OWED_TO_ME -> account.amount
        }
        require(accountAmount.isPositive) { "${debt.side} repayment account leg ${account.amount} points the wrong way" }
        require(!debtLeg.amount.isPositive) { "a repayment grows its debt by ${debtLeg.amount}" }
        val interest = readInterest(legs, debt.side, account.amount.currencyCode)
        require(debtLeg.amount.isNegative || interest != null) { "a repayment with no principal and no interest moves nothing" }
        val principal = -debtLeg.amount
        val pairRate = readPairRate(parts.header.pairRate, accountAmount, principal, interest)
        val category = interest?.categoryTarget()
        Transaction.Repayment(
            id = parts.header.id,
            side = debt.side,
            accountId = account.accountId(),
            debtId = debt.id,
            accountAmount = accountAmount,
            principal = principal,
            interest = interest?.amount,
            interestCategoryId = category?.id,
            interestSubCategoryId = category?.subId,
            interestRateToBase = interest?.rateToBase,
            pairRate = pairRate,
            date = parts.header.date,
            note = parts.header.note,
        )
    }

    /** The interest leg, which must be the role [side] gives and in the account's currency; null when none. */
    private fun readInterest(legs: Map<LegRole, List<Leg>>, side: DebtSide, accountCurrency: String): Leg? {
        val other = interestRole(oppositeOf(side))
        require(other !in legs) { "a $side repayment has a $other leg" }
        val interest = legs[interestRole(side)]?.single() ?: return null
        require(interest.amount.currencyCode == accountCurrency) {
            "interest in ${interest.amount.currencyCode} on an account in $accountCurrency"
        }
        return interest
    }

    /**
     * The stored pair rate, checked against the currencies. In one currency there is none and the
     * account amount must be principal plus interest; across currencies it is required and above zero.
     */
    private fun readPairRate(stored: BigDecimal?, accountAmount: Money, principal: Money, interest: Leg?): BigDecimal? {
        if (accountAmount.currencyCode == principal.currencyCode) {
            require(stored == null) { "a same-currency repayment has no pair rate" }
            val expected = interest?.let { principal + it.amount } ?: principal
            require(accountAmount == expected) { "account amount $accountAmount differs from principal and interest $expected" }
            return null
        }
        val pairRate = requireNotNull(stored) { "a cross-currency repayment needs its pair rate" }
        require(pairRate.signum() > 0) { "repayment pair rate $pairRate is not above zero" }
        return pairRate
    }

    private fun interestRole(side: DebtSide): LegRole = when (side) {
        DebtSide.I_OWE -> LegRole.INTEREST_PAID
        DebtSide.OWED_TO_ME -> LegRole.INTEREST_RECEIVED
    }

    private fun oppositeOf(side: DebtSide): DebtSide = when (side) {
        DebtSide.I_OWE -> DebtSide.OWED_TO_ME
        DebtSide.OWED_TO_ME -> DebtSide.I_OWE
    }

    override fun examples(): List<Transaction.Repayment> = listOf(
        Transaction.Repayment(
            id = 1, side = DebtSide.I_OWE, accountId = 1, debtId = 1, accountAmount = Money.of("450.00", "USD"),
            principal = Money.of("400.00", "USD"), interest = Money.of("50.00", "USD"), interestCategoryId = 7,
            interestRateToBase = BigDecimal.ONE, date = EXAMPLE_DATE, note = "Loan payment",
        ),
        Transaction.Repayment(
            id = 2, side = DebtSide.OWED_TO_ME, accountId = 2, debtId = 2, accountAmount = Money.of("110.00", "EUR"),
            principal = Money.of("100.00", "EUR"), interest = Money.of("10.00", "EUR"), interestCategoryId = 8,
            interestSubCategoryId = 9, interestRateToBase = BigDecimal("1.08370"), date = EXAMPLE_DATE,
        ),
        // Interest only: the debt leg is zero and the account pays just the interest.
        Transaction.Repayment(
            id = 3, side = DebtSide.I_OWE, accountId = 1, debtId = 1, accountAmount = Money.of("50.00", "USD"),
            principal = Money.of("0.00", "USD"), interest = Money.of("50.00", "USD"), interestCategoryId = 7,
            interestRateToBase = BigDecimal.ONE, date = EXAMPLE_DATE,
        ),
        // No interest: the whole amount is principal.
        Transaction.Repayment(
            id = 4, side = DebtSide.OWED_TO_ME, accountId = 1, debtId = 3, accountAmount = Money.of("400.00", "USD"),
            principal = Money.of("400.00", "USD"), date = EXAMPLE_DATE, note = "Paid back in full",
        ),
        // A USD debt repaid from a SYP account, interest in SYP. It carries interest so that a
        // damaged account currency shows against the interest leg.
        Transaction.Repayment(
            id = 5, side = DebtSide.I_OWE, accountId = 3, debtId = 1, accountAmount = Money.of("1300000", "SYP"),
            principal = Money.of("125.00", "USD"), interest = Money.of("50000", "SYP"), interestCategoryId = 7,
            interestRateToBase = BigDecimal("0.00008"), pairRate = BigDecimal("0.0001"), date = EXAMPLE_DATE,
        ),
        // The collecting side across currencies: a USD debt owed to me collected into a SYP account,
        // 1,000,000 SYP of principal at 0.0001 settling 100 USD, plus interest received in SYP.
        Transaction.Repayment(
            id = 6, side = DebtSide.OWED_TO_ME, accountId = 3, debtId = 3, accountAmount = Money.of("1050000", "SYP"),
            principal = Money.of("100.00", "USD"), interest = Money.of("50000", "SYP"), interestCategoryId = 8,
            interestRateToBase = BigDecimal("0.00008"), pairRate = BigDecimal("0.0001"), date = EXAMPLE_DATE,
        ),
    )

    private val EXAMPLE_DATE: LocalDate = LocalDate.of(2026, 6, 15)
}
