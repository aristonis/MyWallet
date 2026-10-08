package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.format.bidiIsolate
import java.math.BigDecimal
import java.text.NumberFormat

/**
 * The rates a foreign-currency entry depends on, right where the entry is made. Without them the
 * only sign of a missing or stale rate is a refused transfer, and fixing it means leaving the form.
 *
 * Shown only when a non-base currency is involved, so an ordinary base-currency entry is unchanged.
 */
@Composable
internal fun RateSection(
    state: AddTransactionUiState,
    onRateChanged: (currencyCode: String, input: String) -> Unit,
) {
    val baseCode = state.fx?.baseCurrencyCode ?: return
    if (state.rateFields.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.rateFields.forEach { field -> RateFieldRow(field, baseCode, onRateChanged) }
        if (state.type == TransactionType.TRANSFER) TransferPreview(state)
        if (state.rateRequired) {
            Text(
                text = stringResource(R.string.add_transaction_rate_required),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * An example in the device's own digits: a fixed "4.5" would be the very input a comma-decimal locale
 * cannot read. Read at call time from the default locale, as the parser does.
 */
private fun exampleRateText(): String = NumberFormat.getInstance().format(BigDecimal("4.5"))

/**
 * Written as the sentence it means, "1 USD = [4] SYP", so the direction of the rate is never in
 * doubt: a rate typed the wrong way round is wildly off and still looks like a plausible number.
 */
@Composable
private fun RateFieldRow(
    field: RateField,
    baseCode: String,
    onRateChanged: (currencyCode: String, input: String) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.add_transaction_rate_lead, bidiIsolate(field.currencyCode)),
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = field.input,
                onValueChange = { onRateChanged(field.currencyCode, it) },
                // Names both currencies so two rate fields on one transfer never read as the same box.
                label = {
                    Text(stringResource(R.string.add_transaction_rate_label, field.currencyCode, baseCode))
                },
                readOnly = !field.isEditable,
                isError = field.isError,
                supportingText = if (field.isError) {
                    { Text(stringResource(R.string.add_transaction_rate_unreadable, exampleRateText())) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Text(text = baseCode, style = MaterialTheme.typography.bodyLarge)
        }
        // Without this a field that ignores typing looks broken rather than deliberately locked.
        if (!field.isEditable) {
            Text(
                text = stringResource(R.string.add_transaction_rate_read_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The rate between the two accounts and what arrives, so the user checks the result, not the math. */
@Composable
private fun TransferPreview(state: AddTransactionUiState) {
    state.pairRate?.let { pair ->
        Text(
            // Isolated as one run so a right-to-left layout keeps "1 USD = 1.1 EUR" in reading order.
            text = bidiIsolate(stringResource(R.string.add_transaction_pair_rate, pair.from, pair.rate, pair.to)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.receivedDisplay?.let { received ->
        Text(
            text = stringResource(R.string.add_transaction_you_receive, bidiIsolate(received)),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
