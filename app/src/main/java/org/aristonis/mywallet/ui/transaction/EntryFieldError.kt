package org.aristonis.mywallet.ui.transaction

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.text

/** Only errors with a single known owning field move from the form message to that field. */
internal fun UiMessage.isInlineEntryError(errorIsAmountInput: Boolean = false): Boolean =
    isAmountEntryError(errorIsAmountInput) || when (this) {
        UiMessage.AccountRequired,
        UiMessage.CategoryRequired,
        UiMessage.DestinationAccountRequired,
        -> true
        else -> false
    }

internal fun UiMessage.isAmountEntryError(errorIsAmountInput: Boolean = false): Boolean = when (this) {
    UiMessage.AmountMissing,
    UiMessage.AmountNotPositive,
    -> true
    UiMessage.AmountOutOfRange,
    UiMessage.NotANumber,
    -> errorIsAmountInput
    else -> false
}

@Composable
internal fun EntryFieldError(message: UiMessage?) {
    if (message == null) return
    Text(
        text = message.text(),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
