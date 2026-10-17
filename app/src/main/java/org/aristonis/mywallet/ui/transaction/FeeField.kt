package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.label
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.text
import org.aristonis.mywallet.ui.theme.WalletTheme

/** Only these messages describe a value entered in the fee control itself. */
internal fun UiMessage.isFeeInputError(): Boolean = when (this) {
    UiMessage.FeeNotBelowAmount,
    UiMessage.FeeAndDeductionsExceedIncome,
    UiMessage.FeeValueNegative,
    UiMessage.FeePercentAboveHundred,
    is UiMessage.FeeTooManyDecimals,
    -> true
    else -> false
}

@Composable
internal fun FeeQuoteError(state: FeeFormState, rateAlreadyExplained: Boolean = false) {
    val error = state.error
    if (error == null || state.errorIsFeeInput ||
        (rateAlreadyExplained && (error == UiMessage.RateMissing || error is UiMessage.MissingRate))) return
    Text(
        text = error.text(),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Optional fee controls and the resulting account movement, shared by the add and edit forms. */
@Composable
internal fun FeeField(
    state: FeeFormState, type: TransactionType, destinationName: String?, currencyCode: String?,
    currencies: List<Currency>, categories: List<Category>, onValueChanged: (String) -> Unit,
    onModeChanged: (FeeMode) -> Unit, onCategorySelected: (Long?) -> Unit, onRetry: () -> Unit,
) {
    if (!type.hasFeeField()) return
    val feeErrorText = state.error?.takeIf { state.errorIsFeeInput }?.text()
    Column(verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap)) {
        TransactionChoiceField(label = stringResource(R.string.fee_mode), selectedText = feeLabel(state.mode),
            items = FeeMode.entries, itemLabel = { feeLabel(it) }, onSelect = onModeChanged)
        Column {
            TextField(value = state.valueInput, onValueChange = onValueChanged,
                label = { Text(stringResource(if (state.mode == FeeMode.PERCENT)
                    R.string.fee_percent_value else R.string.fee_fixed_value)) },
                suffix = { Text(if (state.mode == FeeMode.PERCENT) stringResource(R.string.fee_percent_suffix)
                    else bidiIsolate(currencyCode.orEmpty())) },
                isError = feeErrorText != null, singleLine = true,
                shape = MaterialTheme.shapes.medium, colors = transactionFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().transactionErrorBorder(feeErrorText != null).semantics {
                    if (feeErrorText != null) {
                        error(feeErrorText)
                        liveRegion = LiveRegionMode.Polite
                    }
                })
            Text(
                text = feeErrorText ?: stringResource(if (type == TransactionType.TRANSFER)
                    R.string.fee_transfer_hint else if (type == TransactionType.INCOME)
                    R.string.fee_income_hint else R.string.fee_expense_hint),
                modifier = Modifier.padding(
                    start = WalletTheme.dimensions.screenPadding,
                    top = WalletTheme.dimensions.relatedGap,
                ),
                color = if (feeErrorText != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.needsCategoryPicker || state.categoryId != null || state.categoryRemoved) {
            FeeCategoryPicker(state.categoryId, categories, onCategorySelected)
        }
        if (state.categoryRemoved) Text(stringResource(R.string.fee_category_removed))
        FeeMovement(state, type, destinationName, currencies)
        if (state.amount?.isPositive == true && state.roleError != null) {
            Text(state.roleError.text(), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun feeLabel(mode: FeeMode): String = stringResource(
    if (mode == FeeMode.PERCENT) R.string.fee_percent else R.string.fee_fixed)

@Composable
private fun FeeCategoryPicker(selected: Long?, categories: List<Category>, onSelect: (Long?) -> Unit) {
    val offered = categories.filter { it.kind == CategoryKind.EXPENSE && !it.isSubCategory }
    TransactionChoiceField(label = stringResource(R.string.fee_category),
        selectedText = offered.firstOrNull { it.id == selected }?.label()?.text()?.let(::bidiIsolate)
            ?: stringResource(R.string.fee_uncategorized),
        items = listOf<Category?>(null) + offered,
        itemLabel = { it?.label()?.text()?.let(::bidiIsolate) ?: stringResource(R.string.fee_uncategorized) },
        onSelect = { onSelect(it?.id) })
}

@Composable
private fun FeeMovement(state: FeeFormState, type: TransactionType, destination: String?, currencies: List<Currency>) {
    val movement = state.accountMovement ?: return
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { MoneyFormatter(locale) }
    val amount = bidiIsolate(formatter.display(movement, currencies))
    val text = if (type == TransactionType.INCOME) stringResource(R.string.fee_income_receives, amount)
        else if (type == TransactionType.EXPENSE) stringResource(R.string.fee_expense_pays, amount)
        else stringResource(R.string.fee_transfer_receives,
            bidiIsolate(destination ?: stringResource(R.string.fee_destination)), amount)
    Text(text, style = MaterialTheme.typography.bodyLarge)
}
