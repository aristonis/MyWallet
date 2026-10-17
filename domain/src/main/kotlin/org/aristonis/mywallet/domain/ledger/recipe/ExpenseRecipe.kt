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
import java.math.BigDecimal
import java.time.LocalDate

/**
 * An expense records the full purchase today. The account pays the immediate part and any fee;
 * a deferred part records the remaining category expense against an I-owe debt.
 */
object ExpenseRecipe : EntryRecipe<Transaction.Expense> {

    override val kind: String = "EXPENSE"

    override suspend fun prepareForSave(entry: Transaction.Expense, context: EntrySaveContext): Transaction.Expense =
        entry.copy(
            fee = entry.fee?.takeUnless { it.isZero },
            feeCategoryId = context.feeCategory(entry.fee, entry.feeCategoryId),
            owedDebtId = entry.owed?.let {
                context.debtId(entry.owedDebtId ?: 0, DebtSide.I_OWE, entry.amount.currencyCode)
            },
        )

    private val shape = LegShape(
        required = setOf(LegRole.ACCOUNT, LegRole.MAIN),
        optional = setOf(LegRole.FEE, LegRole.PAID_LATER, LegRole.DEBT),
    )
    override val trackingRuleByRole: Map<LegRole, TrackingRule> = mapOf(
        LegRole.MAIN to TrackingRule.EXPENSE,
        LegRole.PAID_LATER to TrackingRule.EXPENSE,
        LegRole.FEE to TrackingRule.EXPENSE,
    )

    override fun toLegs(entry: Transaction.Expense): EntryParts = EntryParts(
        header = EntryHeader(entry.id, kind, entry.date, entry.note),
        legs = listOf(
            // Unary minus negates the BigDecimal, so the stored text keeps the amount's scale.
            Leg(
                LegRole.ACCOUNT, 0, LegTarget.Account(entry.accountId),
                -(paidNow(entry) + feeOrZero(entry.fee, entry.amount)),
            ),
            Leg(
                LegRole.MAIN, 0, LegTarget.Category(entry.categoryId, entry.subCategoryId),
                paidNow(entry), entry.rateToBase,
            ),
        ) + entry.fee?.let { fee ->
            val category = LegTarget.Category(requireNotNull(entry.feeCategoryId), null)
            listOf(Leg(LegRole.FEE, 0, category, fee, entry.rateToBase))
        }.orEmpty() + entry.owed?.let { owed ->
            listOf(
                Leg(LegRole.PAID_LATER, 0, LegTarget.Category(entry.categoryId, entry.subCategoryId), owed, entry.rateToBase),
                Leg(LegRole.DEBT, 0, LegTarget.Debt(requireNotNull(entry.owedDebtId), DebtSide.I_OWE), owed),
            )
        }.orEmpty(),
    )

    private fun paidNow(entry: Transaction.Expense): Money =
        entry.amount - feeOrZero(entry.owed, entry.amount)

    private fun feeOrZero(fee: Money?, amount: Money): Money = fee ?: Money.zero(amount.currencyCode)

    override fun fromLegs(parts: EntryParts): Transaction.Expense = readParts(parts, kind, shape) { legs ->
        val account = legs.getValue(LegRole.ACCOUNT).single()
        val main = legs.getValue(LegRole.MAIN).single()
        val fee = legs[LegRole.FEE]?.singleOrNull()
        val owed = legs[LegRole.PAID_LATER]?.singleOrNull()
        val debt = legs[LegRole.DEBT]?.singleOrNull()
        require((owed == null) == (debt == null)) { "paid-later and debt legs must appear together" }
        require(main.amount.isPositive || (main.amount.isZero && owed != null)) { "invalid paid-now amount" }
        require(fee == null || !main.amount.isZero) { "a fully deferred expense has no fee" }
        if (owed != null) {
            require(owed.amount.isPositive && owed.amount == debt!!.amount) { "paid-later amount differs from debt" }
            require(owed.categoryTarget() == main.categoryTarget()) { "paid-later category differs from main" }
            require(owed.rateToBase == main.rateToBase) { "paid-later rate differs from main" }
        }
        require(parts.header.pairRate == null) { "an expense has no pair rate" }
        require(fee == null || fee.amount.isPositive) { "expense fee is not positive" }
        val expectedOutflow = main.amount + feeOrZero(fee?.amount, main.amount)
        require(account.amount == -expectedOutflow) {
            "expense categories $expectedOutflow differ from money out ${account.amount}"
        }
        val category = main.categoryTarget()
        val feeCategory = fee?.categoryTarget()
        require(feeCategory?.subId == null) { "expense fee cannot have a sub-category" }
        Transaction.Expense(
            id = parts.header.id,
            accountId = account.accountId(),
            amount = main.amount + feeOrZero(owed?.amount, main.amount),
            categoryId = category.id,
            subCategoryId = category.subId,
            date = parts.header.date,
            note = parts.header.note,
            rateToBase = main.rateToBase,
            fee = fee?.amount,
            feeCategoryId = feeCategory?.id,
            owed = owed?.amount,
            owedDebtId = debt?.debtId(DebtSide.I_OWE),
        )
    }

    override fun examples(): List<Transaction.Expense> = listOf(
        Transaction.Expense(
            id = 1, accountId = 1, amount = Money.of("42.10", "USD"), categoryId = 4,
            date = EXAMPLE_DATE, note = "Groceries", rateToBase = BigDecimal.ONE,
        ),
        Transaction.Expense(
            id = 2, accountId = 2, amount = Money.of("12.345", "KWD"), categoryId = 5, subCategoryId = 6,
            date = EXAMPLE_DATE, rateToBase = BigDecimal("3.25700"),
        ),
        Transaction.Expense(
            id = 4, accountId = 1, amount = Money.of("1000", "USD"), categoryId = 4, subCategoryId = 5,
            date = EXAMPLE_DATE, rateToBase = BigDecimal.ONE, owed = Money.of("700", "USD"), owedDebtId = 1,
            fee = Money.of("3", "USD"), feeCategoryId = 6,
        ),
        Transaction.Expense(
            id = 5, accountId = 1, amount = Money.of("1000", "USD"), categoryId = 4,
            date = EXAMPLE_DATE, rateToBase = BigDecimal.ONE, owed = Money.of("1000", "USD"), owedDebtId = 1,
        ),
        Transaction.Expense(
            id = 3, accountId = 3, amount = Money.of("75000", "SYP"), categoryId = 4,
            date = EXAMPLE_DATE, note = "No rate saved yet",
        ),
    )

    private val EXAMPLE_DATE: LocalDate = LocalDate.of(2026, 2, 14)
}
