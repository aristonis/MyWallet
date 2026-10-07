package org.aristonis.mywallet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.ui.components.WalletListRow
import org.aristonis.mywallet.ui.icons.WalletIcons

/**
 * One line of settings: what it is, what it is currently set to, and a chevron saying there is more
 * behind it.
 *
 * Showing the current value in the row is the point. A page of buttons makes the user open each one
 * to find out what it does; "Base currency — USD" answers the question without a tap.
 */
@Composable
fun SettingsRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    supporting: String? = null,
    icon: ImageVector? = null,
) {
    WalletListRow(
        headline = label,
        supporting = supporting,
        leadingIcon = icon,
        modifier = modifier,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (value != null) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The chevron repeats what tapping the row already implies, so it stays decorative.
                Icon(imageVector = WalletIcons.Forward, contentDescription = null)
            }
        },
        onClick = onClick,
    )
}
