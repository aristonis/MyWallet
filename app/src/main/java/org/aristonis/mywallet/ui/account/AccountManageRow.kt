package org.aristonis.mywallet.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.MenuAction
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.OverflowMenu
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.icons.accountTypeIcon
import org.aristonis.mywallet.ui.theme.WalletTheme

/** One compact management row; its overflow keeps archive and delete guards discoverable. */
@Composable
internal fun AccountManageRow(
    row: ManageAccountRow,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDeleteRequest: () -> Unit,
    onOpen: () -> Unit,
) {
    val dimensions = WalletTheme.dimensions
    val radius = dimensions.cornerMedium
    val shape = RoundedCornerShape(
        topStart = if (isFirst) radius else 0.dp,
        topEnd = if (isFirst) radius else 0.dp,
        bottomStart = if (isLast) radius else 0.dp,
        bottomEnd = if (isLast) radius else 0.dp,
    )
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val amountWidth = with(density) {
        textMeasurer.measure(AnnotatedString(row.balanceDisplay), MaterialTheme.typography.titleMedium)
            .size.width.toDp()
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val remainingNameWidth = maxWidth - dimensions.cardPadding - dimensions.relatedGap -
        dimensions.iconSize - dimensions.iconContentGap - dimensions.minTouchTarget -
        dimensions.relatedGap - amountWidth
    val stacked = density.fontScale >= 1.5f || remainingNameWidth < dimensions.minTouchTarget * 2
    Card(
        // Clipped to the card's shape first, so the tap ripple stops at the rounded corners.
        modifier = Modifier.fillMaxWidth().testTag("account-row-${row.account.id}").clip(shape)
            .clickable(onClickLabel = stringResource(R.string.account_detail_open), onClick = onOpen),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    start = dimensions.cardPadding,
                    end = dimensions.relatedGap,
                    top = dimensions.relatedGap,
                    bottom = dimensions.relatedGap,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = accountTypeIcon(row.account.typeKey),
                    contentDescription = null,
                    modifier = Modifier.size(dimensions.iconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.weight(1f).padding(start = dimensions.iconContentGap),
                    verticalArrangement = Arrangement.spacedBy(dimensions.grid),
                ) {
                    Text(row.account.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            R.string.account_type_and_currency,
                            accountTypeLabel(row.account.typeKey),
                            row.account.currencyCode,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (row.account.archived) {
                        Text(
                            stringResource(R.string.account_archived),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (stacked) {
                        MoneyText(row.balanceDisplay, style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (!stacked) {
                    MoneyText(
                        row.balanceDisplay,
                        modifier = Modifier.padding(start = dimensions.relatedGap),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                OverflowMenu(
                    contentDescription = stringResource(R.string.cd_row_actions, row.account.name),
                    actions = accountActions(row, onEdit, onArchive, onUnarchive, onDeleteRequest),
                )
            }
            if (!isLast) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = dimensions.cardPadding + dimensions.iconSize + dimensions.iconContentGap),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
    }
}

@Composable
private fun accountActions(
    row: ManageAccountRow,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDeleteRequest: () -> Unit,
): List<MenuAction> = buildList {
    add(MenuAction(stringResource(R.string.action_edit), onEdit, WalletIcons.Edit))
    if (row.account.archived) {
        add(MenuAction(stringResource(R.string.action_unarchive), onUnarchive, WalletIcons.Unarchive))
    } else {
        add(MenuAction(stringResource(R.string.action_archive), onArchive, WalletIcons.Archive))
    }
    if (!row.hasTransactions) {
        add(MenuAction(stringResource(R.string.action_delete), onDeleteRequest, WalletIcons.Delete, isDestructive = true))
    }
}
