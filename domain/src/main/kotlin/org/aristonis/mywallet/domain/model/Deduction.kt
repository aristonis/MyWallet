package org.aristonis.mywallet.domain.model

import org.aristonis.mywallet.domain.error.WalletException
import java.math.BigDecimal

/** The way a deduction's typed [DeductionInput.value] is interpreted. */
enum class DeductionMode { PERCENT, FIXED }

/** Whether a deduction reduces the account receipt now or creates a debt to pay later. */
enum class DeductionTiming { NOW, LATER }

private val ONE_HUNDRED = BigDecimal("100")

/**
 * The one value check shared by [DeductionItem] and [DeductionInput]: a percentage is more than 0 and
 * at most 100, a fixed amount is more than 0, and only a fixed amount carries a currency.
 */
private fun requireValidDeductionValue(mode: DeductionMode, value: BigDecimal, currencyCode: String?) {
    when (mode) {
        DeductionMode.PERCENT ->
            if (value.signum() <= 0 || value > ONE_HUNDRED) throw WalletException.DeductionOutOfRange(value)
        DeductionMode.FIXED ->
            if (value.signum() <= 0) throw WalletException.DeductionValueNotPositive(value)
    }
    require((mode == DeductionMode.FIXED) == (currencyCode != null)) {
        "fixed deductions require a currency and percentages do not have one"
    }
}

/**
 * One persisted deduction rule. The item remains separate from [DeductionLine]: changing this
 * rule must never change deductions already recorded on an income entry.
 */
data class DeductionItem(
    val id: Long = 0,
    val name: String,
    val mode: DeductionMode,
    val value: BigDecimal,
    val valueCurrencyCode: String? = null,
    val timing: DeductionTiming,
    val expenseCategoryId: Long,
    val autoAddCategoryIds: Set<Long> = emptySet(),
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "deduction name must not be blank" }
        requireValidDeductionValue(mode, value, valueCurrencyCode)
    }
}

/**
 * A deduction selected for one income before it becomes a persisted [DeductionLine]. It contains
 * no item identity or receipt data, so calculation does not accidentally treat a draft as history.
 */
data class DeductionInput(
    val mode: DeductionMode,
    val value: BigDecimal,
    val valueCurrencyCode: String?,
    val timing: DeductionTiming,
    /** An edited converted amount, in the income currency, used instead of the conversion. */
    val amountOverride: Money? = null,
) {
    init {
        requireValidDeductionValue(mode, value, valueCurrencyCode)
    }
}

/**
 * A snapshot of one deduction on a saved income. [amount] is already expressed in the income
 * currency and rounded at that currency boundary; the remaining fields preserve the owner's input.
 */
data class DeductionLine(
    val itemId: Long,
    val position: Int,
    val name: String,
    val mode: DeductionMode,
    val value: BigDecimal,
    val valueCurrencyCode: String?,
    val timing: DeductionTiming,
    val categoryId: Long,
    val amount: Money,
    val debtId: Long? = null,
)

/** The calculated receipt and per-input amounts for an income before it is saved. */
data class DeductionResult(
    val gross: Money,
    val lines: List<DeductionCalculationLine>,
    val paidNow: Money,
    val accountReceives: Money,
)

/** One calculated deduction amount paired with its input timing. */
data class DeductionCalculationLine(
    val input: DeductionInput,
    val amount: Money,
)
