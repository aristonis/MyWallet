package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Transaction
import java.math.BigDecimal

/** A seller refund credits the account against an expense category, without deductions or fees. */
internal object RefundFormRule : CategoryFormRule(TransactionType.REFUND, CategoryKind.EXPENSE) {
    override fun keptRates(entry: Transaction): Map<String, BigDecimal?> {
        if (entry !is Transaction.Refund) wrongType(this, entry)
        return mapOf(entry.amount.currencyCode to entry.rateToBase)
    }

    override fun newEntry(fields: CategoryEntryFields): Transaction = Transaction.Refund(
        accountId = fields.accountId, amount = fields.amount, categoryId = fields.categoryId,
        subCategoryId = fields.subCategoryId, date = fields.date, note = fields.note,
    )

    override fun edited(loaded: Transaction, fields: CategoryEntryFields): Transaction {
        if (loaded !is Transaction.Refund) wrongType(this, loaded)
        return loaded.copy(accountId = fields.accountId, amount = fields.amount, categoryId = fields.categoryId,
            subCategoryId = fields.subCategoryId, date = fields.date, note = fields.note)
    }

    override fun load(entry: Transaction, moneyParser: MoneyParser): EntryFormInputs {
        if (entry !is Transaction.Refund) wrongType(this, entry)
        return inputsOf(entry.accountId, entry.categoryId, entry.subCategoryId, entry.amount, moneyParser)
    }
}
