package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.message.UiMessage

/** One entry's editable choices, independent of the reusable deduction's current defaults. */
data class IncomeDeductionDraft(
    val itemId: Long,
    val name: String,
    val categoryId: Long,
    val mode: DeductionMode,
    val valueInput: String,
    val valueCurrencyCode: String?,
    val timing: DeductionTiming,
    val selected: Boolean,
    val overrideInput: String = "",
    /** The owner typed in the converted field; it shows their text, even empty, until a reset. */
    val overrideEdited: Boolean = false,
    val historical: DeductionLine? = null,
    /** Explicit edits use current conversion; gross-only edits keep a fixed historical amount. */
    val repriceFixed: Boolean = false,
)

/** The four accounting totals shown before an income is recorded. */
data class IncomeDeductionSummary(
    val gross: Money,
    val deductions: Money,
    val accountReceives: Money,
    val owed: Money,
)

/** A calculated form snapshot, or a safe refusal the owner can correct before saving. */
internal data class IncomeDeductionPreview(
    val lines: List<DeductionLine> = emptyList(),
    val summary: IncomeDeductionSummary? = null,
    val error: UiMessage? = null,
)
