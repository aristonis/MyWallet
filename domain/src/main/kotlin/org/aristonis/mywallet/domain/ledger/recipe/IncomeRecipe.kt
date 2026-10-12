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
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
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

    override suspend fun prepareForSave(entry: Transaction.Income, context: EntrySaveContext): Transaction.Income =
        entry.copy(deductions = entry.deductions.map { line ->
            // Clearing a rounded-zero obligation keeps the snapshot; SaveEntry checks its old debt legs.
            if (line.amount.isZero) line.copy(debtId = null)
            else if (line.timing == DeductionTiming.LATER) {
                line.copy(debtId = context.deductionDebtId(line))
            } else line
        })

    private val shape = LegShape(
        required = setOf(LegRole.ACCOUNT, LegRole.MAIN),
        repeated = setOf(LegRole.DEDUCTION),
        perLine = setOf(LegRole.DEDUCTION_DEBT),
    )
    override val trackingRuleByRole: Map<LegRole, TrackingRule> = mapOf(
        LegRole.MAIN to TrackingRule.INCOME,
        LegRole.DEDUCTION to TrackingRule.EXPENSE,
    )

    override fun toLegs(entry: Transaction.Income): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note),
        legs = listOf(
            Leg(LegRole.ACCOUNT, 0, LegTarget.Account(entry.accountId), entry.accountReceipt()),
            Leg(
                LegRole.MAIN, 0, LegTarget.Category(entry.categoryId, entry.subCategoryId),
                entry.amount, entry.rateToBase,
            ),
        ) + entry.deductions.flatMap { line ->
            listOf(
                Leg(
                    LegRole.DEDUCTION, line.position, LegTarget.Category(line.categoryId, null),
                    line.amount, entry.rateToBase,
                ),
            ) + if (line.timing == DeductionTiming.LATER && line.amount.isPositive) {
                listOf(Leg(
                    LegRole.DEDUCTION_DEBT, line.position,
                    LegTarget.Debt(requireNotNull(line.debtId), DebtSide.I_OWE), line.amount,
                ))
            } else emptyList()
        },
        deductionLines = entry.deductions,
    )

    private fun Transaction.Income.accountReceipt(): Money = deductions
        .filter { it.timing == DeductionTiming.NOW }
        .fold(amount) { remaining, line -> remaining - line.amount }

    override fun fromLegs(parts: EntryParts): Transaction.Income = readParts(parts, kind, shape) { legs ->
        val account = legs.getValue(LegRole.ACCOUNT).single()
        val main = legs.getValue(LegRole.MAIN).single()
        require(parts.header.pairRate == null) { "an income has no pair rate" }
        val category = main.categoryTarget()
        val lines = parts.deductionLines
        require(lines.map { it.position } == lines.indices.toList()) { "income deduction snapshots are not in order" }
        val categoryLines = legs[LegRole.DEDUCTION].orEmpty()
        require(categoryLines.size == lines.size) { "income deductions do not match their category legs" }
        val debtLegs = legs[LegRole.DEDUCTION_DEBT].orEmpty().associateBy { it.position }
        lines.forEachIndexed { index, line ->
            requireLineMatches(line, categoryLines[index], debtLegs[line.position], main)
        }
        requireDebtLegsHaveLines(debtLegs.keys, lines)
        requireReceipt(account, main, lines)
        Transaction.Income(
            id = parts.header.id,
            accountId = account.accountId(),
            amount = main.amount,
            categoryId = category.id,
            subCategoryId = category.subId,
            date = parts.header.date,
            note = parts.header.note,
            rateToBase = main.rateToBase,
            deductions = lines,
        )
    }

    /** One snapshot line against its own value rules, its category leg and (owed later) its debt leg. */
    private fun requireLineMatches(line: DeductionLine, categoryLeg: Leg, debtLeg: Leg?, main: Leg) {
        requireLineIsValid(line, main.amount.currencyCode)
        require(
            categoryLeg.position == line.position && categoryLeg.categoryTarget().id == line.categoryId &&
                categoryLeg.amount == line.amount,
        ) { "deduction ${line.position} differs from its journal leg" }
        if (line.timing == DeductionTiming.LATER && line.amount.isPositive) {
            val debtId = line.debtId
            require(
                debtId != null && debtId > 0 && debtLeg != null &&
                    debtLeg.debtId(DebtSide.I_OWE) == debtId && debtLeg.amount == line.amount,
            ) { "owed-later deduction ${line.position} has no matching debt leg" }
        } else {
            require(line.debtId == null && debtLeg == null) {
                "deduction ${line.position} without an obligation has a debt leg"
            }
        }
    }

    private fun requireLineIsValid(line: DeductionLine, incomeCurrency: String) {
        require(line.itemId > 0 && line.name.isNotBlank() && line.categoryId > 0) {
            "deduction ${line.position} has an invalid identity, name or category"
        }
        val isPercentInRange = line.mode != DeductionMode.PERCENT || line.value <= MAX_PERCENT
        val hasCurrencyOnlyWhenFixed = (line.mode == DeductionMode.FIXED) == (line.valueCurrencyCode != null)
        require(line.value.signum() > 0 && isPercentInRange && hasCurrencyOnlyWhenFixed) {
            "deduction ${line.position} has an invalid value"
        }
        require(!line.amount.isNegative && line.amount.currencyCode == incomeCurrency) {
            "deduction ${line.position} has an invalid amount"
        }
    }

    private fun requireDebtLegsHaveLines(debtPositions: Set<Int>, lines: List<DeductionLine>) {
        val laterPositions = lines.filter { it.timing == DeductionTiming.LATER && it.amount.isPositive }
            .map { it.position }.toSet()
        require(debtPositions == laterPositions) { "income has debt legs without deduction snapshots" }
    }

    /** The deductions stay within the income, and the account got exactly the income less paid-now lines. */
    private fun requireReceipt(account: Leg, main: Leg, lines: List<DeductionLine>) {
        val zero = Money.zero(main.amount.currencyCode)
        val totalDeducted = lines.fold(zero) { total, line -> total + line.amount }
        require(totalDeducted <= main.amount) { "income deductions $totalDeducted exceed gross ${main.amount}" }
        val paidNow = lines.filter { it.timing == DeductionTiming.NOW }
            .fold(zero) { total, line -> total + line.amount }
        val expectedAccount = main.amount - paidNow
        require(!expectedAccount.isNegative && account.amount == expectedAccount) {
            "income receipt ${account.amount} differs from gross less paid-now deductions $expectedAccount"
        }
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
    private val MAX_PERCENT = BigDecimal("100")
}
