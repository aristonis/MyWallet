package org.aristonis.mywallet.ui.reports

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.components.WalletListRow
import org.aristonis.mywallet.ui.icons.WalletIcons

/** Test tags for the Tracking chart links. */
internal object TrackingChartLinkTags {
    const val SPENDING = "openSpendingBreakdown"
    const val NET_WORTH = "openNetWorth"
}

/** Whether Tracking has any chart screen to open; nothing is shown until navigation provides one. */
internal fun hasChartLinks(onOpenSpendingBreakdown: ((TrackingWindow) -> Unit)?, onOpenNetWorth: ((TrackingWindow) -> Unit)?) =
    onOpenSpendingBreakdown != null || onOpenNetWorth != null

/**
 * Rows that open the chart screens on the window Tracking shows. A link appears only when its
 * callback is given, so no control ships before its route is registered.
 */
@Composable
internal fun TrackingChartLinks(
    window: TrackingWindow,
    onOpenSpendingBreakdown: ((TrackingWindow) -> Unit)?,
    onOpenNetWorth: ((TrackingWindow) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val links = listOfNotNull(
        onOpenSpendingBreakdown?.let { open ->
            ChartLink(R.string.tracking_open_spending_breakdown, R.string.tracking_open_spending_breakdown_hint,
                TrackingChartLinkTags.SPENDING, WalletIcons.Categories) { open(window) }
        },
        onOpenNetWorth?.let { open ->
            ChartLink(R.string.tracking_open_net_worth, R.string.tracking_open_net_worth_hint,
                TrackingChartLinkTags.NET_WORTH, WalletIcons.Tracking) { open(window) }
        },
    )
    Column(modifier) {
        links.forEachIndexed { index, link ->
            GroupedLine(isFirst = index == 0, isLast = index == links.lastIndex) {
                WalletListRow(
                    headline = stringResource(link.title),
                    supporting = stringResource(link.hint),
                    leadingIcon = link.icon,
                    // The row's own text names where it goes; the chevron is decoration.
                    trailing = { Icon(WalletIcons.Forward, contentDescription = null) },
                    onClick = link.onClick,
                    modifier = Modifier.testTag(link.tag),
                )
            }
        }
    }
}

private class ChartLink(
    @StringRes val title: Int,
    @StringRes val hint: Int,
    val tag: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)
