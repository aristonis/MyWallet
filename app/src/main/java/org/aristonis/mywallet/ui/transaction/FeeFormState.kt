package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.message.UiMessage

/** One entry's optional fee, priced in the source currency independently of the account default. */
data class FeeFormState(
    val mode: FeeMode = FeeMode.FIXED,
    val valueInput: String = "",
    val categoryId: Long? = null,
    /** The previously chosen category disappeared; make its fallback visible before saving. */
    val categoryRemoved: Boolean = false,
    val amount: Money? = null,
    /** What the destination receives on a transfer, or what the source pays on an expense. */
    val accountMovement: Money? = null,
    val error: UiMessage? = null,
    /** True when [error] came from the fee value, rather than a rate or transfer receipt. */
    val errorIsFeeInput: Boolean = false,
    val needsCategoryPicker: Boolean = false,
    val roleLoading: Boolean = false,
    val roleError: UiMessage? = null,
    /** The owner typed a value or picked a mode, so a new source account's default must not replace it. */
    val touched: Boolean = false,
) {
    /** A missing role lookup matters only when the entry actually charges a nonzero fee. */
    val allowsSave: Boolean get() = error == null &&
        (amount?.isPositive != true || (!roleLoading && roleError == null))
}

/** Whether the kind can charge an entry fee; other kinds never consume its draft. */
internal fun TransactionType?.hasFeeField(): Boolean =
    this == TransactionType.EXPENSE || this == TransactionType.TRANSFER

/** A fee is an expense, and the ledger books it on a top-level category only. */
private fun Category.takesFee(): Boolean = kind == CategoryKind.EXPENSE && !isSubCategory

/** A removed or wrong-kind category cannot remain hidden in a draft's saved fee. */
internal fun FeeFormState.withCategories(categories: List<Category>): FeeFormState {
    val picked = categoryId ?: return this
    return if (categories.any { it.id == picked && it.takesFee() }) this
        else copy(categoryId = null, categoryRemoved = true)
}

internal fun FeeFormState.withValue(value: String): FeeFormState = copy(valueInput = value, touched = true)

/** The digits typed for one mode mean nothing in the other (2 percent is not 2 dollars). */
internal fun FeeFormState.withMode(mode: FeeMode): FeeFormState =
    if (mode == this.mode) this else copy(mode = mode, valueInput = "", touched = true)

/** The owner's category for this fee, or null when [id] is not an offered fee category. */
internal fun FeeFormState.withCategory(id: Long?, categories: List<Category>): FeeFormState? =
    if (id == null || categories.any { it.id == id && it.takesFee() }) {
        copy(categoryId = id, categoryRemoved = false)
    } else null

internal fun FeeFormState.withRoleLoading(): FeeFormState = copy(roleLoading = true, roleError = null)

/**
 * The Fees lookup finished. A missing binding, or one pointing at a category that no longer exists,
 * needs a picker so the owner can see where the fee will be filed.
 */
internal fun FeeFormState.withRole(id: Long?, message: UiMessage?, categories: List<Category>): FeeFormState =
    copy(roleLoading = false, roleError = message, needsCategoryPicker = id == null || categories.none { it.id == id })

/** A fresh fee for a new entry or another type keeps what is known about the Fees lookup. */
internal fun FeeFormState.restartedFrom(fresh: FeeFormState): FeeFormState =
    fresh.copy(roleLoading = roleLoading, roleError = roleError, needsCategoryPicker = needsCategoryPicker)

/**
 * A fixed fee is in the source currency, so moving that currency clears its old digits; nothing the
 * owner typed is left, so the fee counts as untouched again.
 */
internal fun FeeFormState.onCurrencyChanged(oldCode: String?, newCode: String?): FeeFormState =
    if (mode == FeeMode.FIXED && oldCode != newCode) copy(valueInput = "", touched = false) else this

/**
 * A new transfer's source moved: a fee the owner set stays (only a fixed fee in the old currency is
 * cleared), and an untouched one follows the new source's [default].
 */
internal fun FeeFormState.followingSource(default: FeeFormState, oldCode: String?, newCode: String?): FeeFormState {
    val moved = onCurrencyChanged(oldCode, newCode)
    return if (moved.touched) moved else moved.copy(mode = default.mode, valueInput = default.valueInput)
}
