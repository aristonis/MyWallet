package org.aristonis.mywallet.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error as semanticsError
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.FeeRule
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.WalletTheme

/**
 * Parses a locale-aware exact value; None or blank removes the default. Negative values are refused
 * here, and [FeeRule] refuses a percent above 100, so a bad default never reaches a transfer.
 */
internal fun parseDefaultFee(mode: FeeMode?, input: String, currencyCode: String, parser: MoneyParser): FeeRule? {
    if (mode == null || input.isBlank()) return null
    // The opening-balance flavor is the only one that accepts zero (a zero default is valid); it also
    // accepts a negative sign, which is refused just below with the fee's own message.
    val parsed = parser.parseOpeningBalance(input, currencyCode)
    // A percent is not money, but the account currency is the only code the typed error can carry.
    if (parsed.isNegative) throw WalletException.FeeValueNegative(parsed)
    return FeeRule(mode, parsed.amount)
}

/** A typed value belongs to its mode (2.125 % is not 2.125 EUR); picking the same mode again keeps it. */
internal fun feeInputAfterModeChange(current: FeeMode?, picked: FeeMode?, input: String): String =
    if (current == picked) input else ""

/**
 * A fixed fee has no currency of its own: it is read in the account currency, so after a currency
 * switch the old digits would silently mean a different amount. A percent is unaffected.
 */
internal fun feeInputAfterCurrencyChange(mode: FeeMode?, oldCode: String?, newCode: String, input: String): String =
    if (mode == FeeMode.FIXED && oldCode != newCode) "" else input

/** Account transfer-fee controls; fixed values use the selected account currency. */
@Composable
internal fun AccountFeeEditor(
    mode: FeeMode?,
    value: String,
    currencyCode: String?,
    onModeChanged: (FeeMode?) -> Unit,
    onValueChanged: (String) -> Unit,
    enabled: Boolean = true,
    error: UiMessage? = null,
) {
    val errorText = error?.text()
    Column(verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap)) {
        AccountDropdownField(
            label = stringResource(R.string.account_default_fee),
            selectedText = feeModeLabel(mode),
            items = listOf(null, FeeMode.PERCENT, FeeMode.FIXED),
            itemLabel = { feeModeLabel(it) },
            onSelect = onModeChanged,
            enabled = enabled,
        )
        if (mode != null) {
            TextField(
                value = value,
                onValueChange = onValueChanged,
                enabled = enabled,
                label = {
                    val label = if (mode == FeeMode.PERCENT) R.string.fee_percent_value else R.string.fee_fixed_value
                    Text(stringResource(label))
                },
                suffix = { Text(if (mode == FeeMode.PERCENT) "%" else bidiIsolate(currencyCode.orEmpty())) },
                isError = errorText != null,
                shape = MaterialTheme.shapes.medium,
                colors = accountFieldColors(),
                supportingText = {
                    Column {
                        errorText?.let { Text(it) }
                        Text(stringResource(R.string.account_default_fee_hint))
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().accountErrorOutline(errorText != null)
                    .semantics { errorText?.let { semanticsError(it) } },
            )
        }
    }
}

@Composable
private fun feeModeLabel(mode: FeeMode?): String = stringResource(
    when (mode) {
        null -> R.string.fee_none
        FeeMode.PERCENT -> R.string.fee_percent
        FeeMode.FIXED -> R.string.fee_fixed
    },
)
