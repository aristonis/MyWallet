package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.DeductionInput
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.DeductionItemRepository

/** Validate every draft before resolving debts or creating role fallback categories. */
internal suspend fun requireSaveValues(
    entry: Transaction,
    snapshot: FxSnapshot,
    deductions: DeductionItemRepository,
    debt: DebtRef? = null,
) {
    when (entry) {
        is Transaction.Income -> {
            if (entry.amount.currencyCode !in snapshot.decimalPlaces) {
                throw WalletException.CurrencyNotFound(entry.amount.currencyCode)
            }
            requireFeeValue(entry.fee)
            if (entry.fee != null && entry.fee >= entry.amount) {
                throw WalletException.FeeNotBelowAmount(entry.amount, entry.fee)
            }
            requireDeductionItems(entry, deductions)
            requireDeductionValues(entry, snapshot)
        }
        is Transaction.Expense -> {
            requireFeeValue(entry.fee)
            if (entry.owed != null) {
                if (debt?.counterparty?.isBlank() == true) throw WalletException.NameBlank()
                if (entry.owed == entry.amount && entry.fee?.isPositive == true) {
                    throw WalletException.PaidLaterFeeWithoutPaidNow()
                }
            }
        }
        is Transaction.Transfer -> {
            requireFeeValue(entry.fee)
            if (entry.fee != null && entry.fee >= entry.sourceAmount) {
                throw WalletException.FeeNotBelowAmount(entry.sourceAmount, entry.fee)
            }
        }
        else -> Unit
    }
}

/** Check only identity against live rules; names, values and categories remain historical snapshots. */
internal suspend fun requireDeductionItems(income: Transaction.Income, items: DeductionItemRepository) {
    income.deductions.map { it.itemId }.distinct().forEach { id ->
        if (items.findById(id) == null) throw WalletException.DeductionItemNotFound(id)
    }
}

private fun requireDeductionValues(income: Transaction.Income, snapshot: FxSnapshot) {
    income.deductions.forEachIndexed { position, line ->
        if (line.position != position || line.name.isBlank() || line.categoryId <= 0) {
            throw WalletException.DeductionSnapshotInvalid()
        }
        if ((line.mode == DeductionMode.FIXED) != (line.valueCurrencyCode != null)) {
            throw WalletException.DeductionSnapshotInvalid()
        }
        // Reuse the value contract without recalculating an owner's edited converted amount.
        DeductionInput(line.mode, line.value, line.valueCurrencyCode, line.timing)
        line.valueCurrencyCode?.let { code ->
            if (code !in snapshot.decimalPlaces) throw WalletException.CurrencyNotFound(code)
        }
        if (line.amount.isNegative) throw WalletException.DeductionSnapshotInvalid()
        if (line.amount.currencyCode != income.amount.currencyCode) {
            throw WalletException.CurrencyMismatch(line.amount.currencyCode, income.amount.currencyCode)
        }
        if (line.timing == DeductionTiming.NOW && line.debtId != null) {
            throw WalletException.DeductionSnapshotInvalid()
        }
    }
    val total = income.deductions.fold(Money.zero(income.amount.currencyCode)) { sum, line -> sum + line.amount }
    if (total > income.amount) throw WalletException.DeductionsExceedIncome(income.amount, total)
    val totalWithFee = total + (income.fee ?: Money.zero(income.amount.currencyCode))
    if (totalWithFee > income.amount) {
        throw WalletException.FeeAndDeductionsExceedIncome(income.amount, totalWithFee)
    }
}

/** The model already holds the fee to the entry's currency; its sign is checked here, as a typed error. */
private fun requireFeeValue(fee: Money?) {
    if (fee != null && fee.isNegative) throw WalletException.FeeValueNegative(fee)
}
