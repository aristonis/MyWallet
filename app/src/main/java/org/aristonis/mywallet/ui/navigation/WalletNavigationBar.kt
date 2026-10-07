package org.aristonis.mywallet.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * The four top-level destinations.
 *
 * Each item is labelled as well as drawn: an icon alone leaves the user guessing, and the label is
 * what a screen reader announces. Tapping the tab you are already on is a no-op rather than a second
 * copy of it — that is [onSelect]'s job, via `launchSingleTop`.
 */
@Composable
fun WalletNavigationBar(
    selected: WalletTab?,
    onSelect: (WalletTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        WalletTab.entries.forEach { tab ->
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                // The label is right there, so repeating it as a description would only make a
                // screen reader say it twice.
                icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                label = { Text(label) },
            )
        }
    }
}
