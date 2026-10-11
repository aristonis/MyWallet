package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction

/**
 * The money that moved, the figure an entry is shown by: what left or reached the account, or for a
 * kind without one, how much the debt changed. A repayment shows its account side, which includes
 * any interest; a transfer shows what left the source.
 */
internal fun Transaction.movedAmount(): Money = when (this) {
    is Transaction.Income -> amount
    is Transaction.Expense -> amount
    is Transaction.Refund -> amount
    is Transaction.Transfer -> sourceAmount
    is Transaction.Borrow -> amount
    is Transaction.Lend -> amount
    is Transaction.OwedBefore -> amount
    is Transaction.CreditPurchase -> amount
    is Transaction.EarnedUnpaid -> amount
    is Transaction.Repayment -> accountAmount
    is Transaction.WriteOff -> amount
}

/**
 * The part of an entry that counts in Tracking, in the entry's own currency, or null when none of it
 * does. Borrowing, lending and an opening balance change what is owed but earn or cost nothing; a
 * repayment only counts its interest, because paying back principal settles a debt and is not an
 * expense (nor collecting it income).
 */
internal fun Transaction.countedAmount(): Money? = when (this) {
    is Transaction.Income -> amount
    is Transaction.Expense -> amount
    is Transaction.Refund -> -amount
    is Transaction.Transfer -> null
    is Transaction.Borrow -> null
    is Transaction.Lend -> null
    is Transaction.OwedBefore -> null
    is Transaction.CreditPurchase -> amount
    is Transaction.EarnedUnpaid -> amount
    is Transaction.Repayment -> interest
    is Transaction.WriteOff -> amount
}
