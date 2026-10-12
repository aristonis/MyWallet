package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.format.AmountRole
import org.aristonis.mywallet.ui.format.amountRole
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.label
import java.math.BigDecimal

/**
 * The names and currencies the rows are dressed in; the same for every window. Accounts and
 * categories are keyed by id once per emission, so resolving a row is a map lookup rather than a
 * scan of the whole list for every row.
 */
internal data class RowLookups(
    val accountsById: Map<Long, Account>,
    val categoriesById: Map<Long, Category>,
    val currencies: List<Currency>,
    val baseCurrencyCode: String?,
) {
    // A deleted/unknown id degrades to a gap instead of crashing the whole list (defensive read).
    // Nothing here decides what a gap looks like — that would be copy, and copy lives in the UI.
    fun accountName(id: Long): String? = accountsById[id]?.name

    fun categoryLabel(id: Long): CategoryLabel = categoriesById[id]?.label() ?: CategoryLabel.Unknown
}

/** The parts of a row that differ by kind; the amounts every row has are filled in by [toRow]. */
private data class RowShape(
    val accountName: String?,
    val categoryLabel: CategoryLabel?,
    val destAccountName: String? = null,
    val destAmount: Money? = null,
)

/**
 * This entry as a display row. Only a transfer has a destination; a kind with no account (an opening
 * balance, a purchase on credit) has no account name, and one with no category has no label.
 */
internal fun Transaction.toRow(lookups: RowLookups, moneyFormatter: MoneyFormatter): TransactionRow {
    val shape = rowShape(lookups)
    val amount = movedAmount()
    val counted = countedAmount()
    val display = { money: Money -> moneyFormatter.display(money, lookups.currencies) }
    val amountDisplay = display(amount)
    // Most kinds count exactly the money that moved; its text is reused rather than formatted twice.
    val countedDisplay = if (counted == amount) amountDisplay else counted?.let(display)
    return TransactionRow(
        id = id, date = date, type = rowType(),
        accountName = shape.accountName,
        destAccountName = shape.destAccountName,
        categoryLabel = shape.categoryLabel,
        amount = amount, destAmount = shape.destAmount,
        amountDisplay = amountDisplay, destAmountDisplay = shape.destAmount?.let(display),
        note = note,
        countedAmount = counted, countedAmountDisplay = countedDisplay,
        countedAmountRole = counted?.let { countedRole() },
        countedBaseAmountDisplay = counted?.let { baseDisplay(it, countedRate(), lookups, moneyFormatter) },
        deductions = (this as? Transaction.Income)?.deductions.orEmpty().map { line ->
            TransactionDeductionRow(
                name = line.name, timing = line.timing, amount = line.amount,
                amountDisplay = display(line.amount),
                baseAmountDisplay = baseDisplay(line.amount, countedRate(), lookups, moneyFormatter),
            )
        },
        feeDisplay = feeAmount()?.takeIf { it.isPositive }?.let(display),
        feeBaseAmountDisplay = feeAmount()?.takeIf { it.isPositive }?.let {
            baseDisplay(it, feeRate(), lookups, moneyFormatter)
        },
    )
}

/**
 * Which kind of row this entry is shown as; the edit screen names a read-only entry by it too. A
 * repayment and a write-off split by side, since paying and collecting (or a loss and a forgiven
 * debt) are opposite movements that one row type could not tell apart.
 */
internal fun Transaction.rowType(): TransactionRowType = when (this) {
    is Transaction.Income -> TransactionRowType.INCOME
    is Transaction.Expense -> TransactionRowType.EXPENSE
    is Transaction.Refund -> TransactionRowType.REFUND
    is Transaction.Transfer -> TransactionRowType.TRANSFER
    is Transaction.Borrow -> TransactionRowType.BORROW
    is Transaction.Lend -> TransactionRowType.LEND
    is Transaction.OwedBefore -> TransactionRowType.OWED_BEFORE
    is Transaction.CreditPurchase -> TransactionRowType.CREDIT_PURCHASE
    is Transaction.EarnedUnpaid -> TransactionRowType.EARNED_UNPAID
    is Transaction.Repayment -> when (side) {
        DebtSide.I_OWE -> TransactionRowType.REPAYMENT_PAID
        DebtSide.OWED_TO_ME -> TransactionRowType.REPAYMENT_RECEIVED
    }
    is Transaction.WriteOff -> when (side) {
        DebtSide.OWED_TO_ME -> TransactionRowType.WRITE_OFF_LOSS
        DebtSide.I_OWE -> TransactionRowType.WRITE_OFF_FORGIVEN
    }
}

private fun Transaction.rowShape(lookups: RowLookups): RowShape = when (this) { // exhaustive — no `else`
    is Transaction.Income -> RowShape(lookups.accountName(accountId), lookups.categoryLabel(categoryId))
    is Transaction.Expense -> RowShape(lookups.accountName(accountId), lookups.categoryLabel(categoryId))
    is Transaction.Refund -> RowShape(lookups.accountName(accountId), lookups.categoryLabel(categoryId))
    is Transaction.Transfer -> RowShape(
        lookups.accountName(sourceAccountId), categoryLabel = null,
        destAccountName = lookups.accountName(destAccountId), destAmount = destAmount,
    )
    is Transaction.Borrow -> RowShape(lookups.accountName(accountId), categoryLabel = null)
    is Transaction.Lend -> RowShape(lookups.accountName(accountId), categoryLabel = null)
    is Transaction.OwedBefore -> RowShape(accountName = null, categoryLabel = null)
    is Transaction.CreditPurchase -> RowShape(accountName = null, lookups.categoryLabel(categoryId))
    is Transaction.EarnedUnpaid -> RowShape(accountName = null, lookups.categoryLabel(categoryId))
    is Transaction.Repayment ->
        RowShape(lookups.accountName(accountId), categoryLabel = interestCategoryId?.let(lookups::categoryLabel))
    is Transaction.WriteOff -> RowShape(accountName = null, lookups.categoryLabel(categoryId))
}

/** Counted values use receipt rates; missing rates never fall back to a current quote. */
private fun baseDisplay(
    amount: Money,
    rate: BigDecimal?,
    lookups: RowLookups,
    formatter: MoneyFormatter,
): String? {
    val base = lookups.baseCurrencyCode ?: return null
    if (amount.currencyCode == base || rate == null) return null
    return formatter.display(Money.of(amount.amount.abs().multiply(rate), base), lookups.currencies)
}

private fun Transaction.countedRole(): AmountRole = when (this) {
    is Transaction.Repayment -> if (side == DebtSide.I_OWE) AmountRole.EXPENSE else AmountRole.INCOME
    else -> rowType().amountRole()
}

private fun Transaction.countedRate(): BigDecimal? = when (this) {
    is Transaction.Income -> rateToBase
    is Transaction.Expense -> rateToBase
    is Transaction.Refund -> rateToBase
    is Transaction.CreditPurchase -> rateToBase
    is Transaction.EarnedUnpaid -> rateToBase
    is Transaction.Repayment -> interestRateToBase
    is Transaction.WriteOff -> rateToBase
    else -> null
}

private fun Transaction.feeAmount(): Money? = when (this) {
    is Transaction.Expense -> fee
    is Transaction.Transfer -> fee
    else -> null
}

private fun Transaction.feeRate(): BigDecimal? = when (this) {
    is Transaction.Expense -> rateToBase
    is Transaction.Transfer -> feeRateToBase
    else -> null
}
