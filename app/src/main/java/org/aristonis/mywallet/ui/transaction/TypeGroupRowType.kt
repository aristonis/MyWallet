package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.model.TypeGroup

internal fun TransactionRowType.typeGroup(): TypeGroup = when (this) {
    TransactionRowType.INCOME, TransactionRowType.EARNED_UNPAID,
    TransactionRowType.WRITE_OFF_FORGIVEN -> TypeGroup.INCOME
    TransactionRowType.EXPENSE, TransactionRowType.REFUND, TransactionRowType.CREDIT_PURCHASE,
    TransactionRowType.WRITE_OFF_LOSS -> TypeGroup.EXPENSE
    TransactionRowType.TRANSFER -> TypeGroup.TRANSFER
    TransactionRowType.BORROW, TransactionRowType.LEND, TransactionRowType.OWED_BEFORE,
    TransactionRowType.REPAYMENT_PAID, TransactionRowType.REPAYMENT_RECEIVED -> TypeGroup.DEBTS
}
