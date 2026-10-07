package org.aristonis.mywallet.ui.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.aristonis.mywallet.ui.icons.WalletIcons

/** One entry in an [OverflowMenu]. */
data class MenuAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    /** Drawn in the error colour. Reserved for actions that remove something. */
    val isDestructive: Boolean = false,
)

/**
 * A row's actions, behind one button.
 *
 * A row of text buttons looks fine at the default font size and falls apart at 200%, where three
 * labels cannot share a phone's width: they wrap, overlap, or push each other off the edge, and an
 * action the user can no longer reach may as well not exist. A menu costs one extra tap and always
 * fits, because the labels get the full width of a popup instead of a third of a row.
 *
 * [contentDescription] should name the row ("Actions for Cash"), since several of these appear on a
 * screen and "More" repeated eight times tells a screen-reader user nothing.
 */
@Composable
fun OverflowMenu(
    contentDescription: String,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }, modifier = modifier) {
        Icon(imageVector = WalletIcons.More, contentDescription = contentDescription)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = action.label,
                        color = if (action.isDestructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                },
                leadingIcon = action.icon?.let {
                    {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            tint = if (action.isDestructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                onClick = {
                    expanded = false
                    action.onClick()
                },
            )
        }
    }
}
