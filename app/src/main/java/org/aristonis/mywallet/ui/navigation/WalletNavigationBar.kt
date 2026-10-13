package org.aristonis.mywallet.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

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
    BoxWithConstraints(modifier = modifier) {
        val isLargeText = LocalDensity.current.fontScale >= 1.3f
        val useTwoRows = maxWidth < 600.dp && isLargeText
        NavigationBar {
            if (useTwoRows) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    WalletTab.entries.chunked(2).forEach { tabs ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            tabs.forEach { tab ->
                                LargeTextNavigationItem(tab, selected, onSelect)
                            }
                        }
                    }
                }
            } else {
                WalletTab.entries.forEach { tab ->
                    if (isLargeText) {
                        LargeTextNavigationItem(tab, selected, onSelect)
                    } else {
                        WalletNavigationItem(tab, selected, onSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.LargeTextNavigationItem(
    tab: WalletTab,
    selected: WalletTab?,
    onSelect: (WalletTab) -> Unit,
) {
    val isSelected = tab == selected
    val label = stringResource(tab.labelRes)
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 80.dp)
            .selectable(selected = isSelected, onClick = { onSelect(tab) }, role = Role.Tab)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    RoundedCornerShape(50),
                )
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun RowScope.WalletNavigationItem(
    tab: WalletTab,
    selected: WalletTab?,
    onSelect: (WalletTab) -> Unit,
) {
    val label = stringResource(tab.labelRes)
    NavigationBarItem(
        selected = tab == selected,
        onClick = { onSelect(tab) },
        // The visible label already provides the accessible name.
        icon = { Icon(imageVector = tab.icon, contentDescription = null) },
        label = { Text(label) },
    )
}
