package org.aristonis.mywallet.ui.reports.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.aristonis.mywallet.ui.components.AristonisLoader
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletListRow
import org.aristonis.mywallet.ui.theme.WalletTheme

/** Test tags shared by the chart screens' tests. */
internal object ChartTags {
    const val NET_WORTH_CHART = "netWorthChart"
    const val SPENDING_CHART = "spendingChart"
    const val RESTATEMENT_LABEL = "netWorthRestatement"
    const val HERO = "chartHero"
    const val UPDATING = "chartUpdating"
}

/**
 * Marks a node whose text a screen reader should read out when it changes: a new total after the
 * window moves, a failure, a Retry that turned into figures. Polite, so it never cuts speech off.
 */
internal fun Modifier.announced(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/**
 * The figure a chart screen leads with: what it is and in which currency, the exact amount, and what
 * the amount stands for. The amount wraps rather than shrinking or cutting off, so every digit stays.
 * It is read as one announcement.
 */
@Composable
internal fun ChartHero(heading: String, amount: String, caption: String, modifier: Modifier = Modifier) {
    val dimensions = WalletTheme.dimensions
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .announced(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(dimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
        ) {
            Text(heading, style = MaterialTheme.typography.labelLarge)
            MoneyText(amount = amount, style = MaterialTheme.typography.headlineSmall)
            Text(caption, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Waiting for exact totals in the bounded space below the chart controls. */
@Composable
internal fun ChartLoading(modifier: Modifier = Modifier) {
    AristonisLoader(modifier)
}

/** A rate is missing: say so, show no converted figure, and offer the screen that fixes it. */
@Composable
internal fun ChartMissingRate(message: String, onManageRates: () -> Unit, modifier: Modifier = Modifier) {
    val dimensions = WalletTheme.dimensions
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap)) {
        StatusCard(
            tone = StatusTone.WARNING,
            message = message,
            modifier = Modifier.announced(),
            iconDescription = stringResource(R.string.cd_warning),
        )
        TextButton(onClick = onManageRates, modifier = Modifier.heightIn(min = dimensions.minTouchTarget)) {
            Text(stringResource(R.string.tracking_manage_rates))
        }
    }
}

/**
 * Shown right after the window changes, while the figures below still belong to the previous window.
 * The indicator says so in words, so it is not a bare spinner to a screen reader.
 */
@Composable
internal fun ChartUpdating(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.chart_updating)
    LinearProgressIndicator(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description }
            .announced(),
    )
}

/** One line of an exact table: what it is, a quieter detail, and the exact amount. */
@Composable
internal fun ExactAmountRow(
    headline: String,
    supporting: String?,
    amount: String,
    icon: ImageVector? = null,
    iconDescription: String? = null,
) {
    WalletListRow(
        headline = headline,
        supporting = supporting,
        leadingIcon = icon,
        leadingIconDescription = iconDescription,
        trailing = { MoneyText(amount, style = MaterialTheme.typography.titleMedium) },
    )
}
