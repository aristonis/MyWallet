package org.aristonis.mywallet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.theme.MyWalletTheme

private val CONTENT_SPACING = 16.dp
private val MIN_TOUCH_TARGET = 48.dp

/**
 * What a screen shows in place of its data when reading it failed. An empty list there would be a
 * false statement ("you have nothing"), so the screen says the read failed and offers to try again:
 * the cause may have been passing (a busy disk, a restore still finishing), and without a Retry the
 * only way back would be leaving the screen.
 *
 * The message sits in an error [StatusCard], so the state carries an icon as well as a colour. The
 * card and the button follow the layout direction, so the state mirrors as a whole in a right-to-left
 * locale.
 */
@Composable
fun LoadFailedState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CONTENT_SPACING),
    ) {
        // The text says what went wrong, so the icon beside it is decoration for a screen reader.
        StatusCard(tone = StatusTone.ERROR, message = message)
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET)) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoadFailedStatePreview() {
    MyWalletTheme(dynamicColor = false) {
        LoadFailedState(message = stringResource(R.string.transactions_data_unreadable), onRetry = {})
    }
}
