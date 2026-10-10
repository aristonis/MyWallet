package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.format.rememberDateFormatter
import org.aristonis.mywallet.ui.message.text

/**
 * An entry of a kind the edit form cannot change yet, shown as it is: its kind, the money that moved,
 * its date and note, each disabled, and a Save that never enables. One line says why, so a dead
 * button is never left unexplained. There is no Delete: removing a debt entry must keep its debt from
 * going below zero, which this screen does not check.
 */
@Composable
internal fun ReadOnlyEntry(state: EditTransactionUiState, loaded: Transaction) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            kindLabel(loaded.rowType()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.edit_transaction_read_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DisabledField(
            label = stringResource(R.string.field_amount_in_currency, loaded.movedAmount().currencyCode),
            value = state.amountInput,
            textStyle = MaterialTheme.typography.headlineSmall,
        )
        val formatDate = rememberDateFormatter()
        DisabledField(label = stringResource(R.string.field_date), value = formatDate(state.date ?: loaded.date))
        DisabledField(label = stringResource(R.string.field_note), value = state.note)

        state.error?.let { message ->
            Text(text = message.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.weight(1f))

        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_save_transaction))
        }
    }
}

@Composable
private fun DisabledField(
    label: String,
    value: String,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        label = { Text(label) },
        enabled = false,
        singleLine = true,
        textStyle = textStyle,
        modifier = Modifier.fillMaxWidth(),
    )
}
