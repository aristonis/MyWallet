package org.aristonis.mywallet.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.theme.WalletTheme

/** Between a heading and its content. Larger gaps separate whole regions; see docs/Design.md 2.3. */
private val SECTION_SPACING = 8.dp
private val CARD_PADDING = 16.dp

/**
 * A titled band with optional actions on the trailing side ("Accounts … Manage"). Actions sit at the
 * end rather than on the right, so they follow the reading direction into a mirrored layout.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/**
 * One record in a list: a leading type icon, a name, a quieter second line, and whatever belongs at
 * the end (usually a [MoneyText]). Built on Material's `ListItem` so height, padding and the touch
 * target follow the platform rather than being re-guessed per screen.
 */
@Composable
fun WalletListRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leadingIcon: ImageVector? = null,
    leadingIconDescription: String? = null,
    leadingIconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        headlineContent = { Text(headline, style = MaterialTheme.typography.titleMedium) },
        supportingContent = supporting?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = leadingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = leadingIconDescription,
                    tint = leadingIconTint,
                )
            }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** How urgent a [StatusCard] is. Each tone brings its own icon so colour never carries it alone. */
enum class StatusTone { WARNING, ERROR, SUCCESS, INFO }

/**
 * A recoverable situation the user needs to know about, and — when [onClick] is given — can act on
 * by tapping it. Every state that stops the wallet from showing a real number goes through here, so
 * a missing rate looks the same wherever it surfaces.
 */
@Composable
fun StatusCard(
    tone: StatusTone,
    message: String,
    modifier: Modifier = Modifier,
    iconDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val container = when (tone) {
        StatusTone.WARNING -> WalletTheme.colors.warningContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        StatusTone.INFO -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (tone) {
        StatusTone.WARNING -> WalletTheme.colors.onWarningContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (tone) {
        StatusTone.WARNING -> WalletIcons.Warning
        StatusTone.ERROR -> WalletIcons.Error
        StatusTone.SUCCESS -> WalletIcons.Success
        StatusTone.INFO -> WalletIcons.About
    }

    Card(
        modifier = if (onClick != null) {
            modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            modifier.fillMaxWidth()
        },
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(
            modifier = Modifier.padding(CARD_PADDING),
            horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = iconDescription, tint = content)
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * What a list says when it has nothing in it. Always names the next action, because an empty screen
 * that only says "nothing here" leaves the user to guess where the button is.
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CARD_PADDING),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
