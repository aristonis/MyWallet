package org.aristonis.mywallet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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

/**
 * A setting that is simply on or off. The whole row is the toggle, announced as a switch, so the
 * target is the full row rather than the small switch and a screen reader hears one control with its
 * label and state instead of a label followed by an unnamed switch.
 */
@Composable
fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
) {
    WalletListRow(
        headline = label,
        supporting = supporting,
        leadingIcon = icon,
        modifier = modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        // The row carries the click and the announcement, so the switch itself is inert.
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}
