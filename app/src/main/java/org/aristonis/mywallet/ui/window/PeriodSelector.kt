package org.aristonis.mywallet.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.ui.icons.WalletIcons

/**
 * Where the segmented row stops being usable. Below this the five labels still share the width
 * legibly; at and above it the longest of them no longer fits its fifth of a compact screen.
 */
internal const val LARGE_TEXT_SCALE = 1.3f

/**
 * Which period the report covers; [selected] is null while a custom range is in effect, which is
 * none of the five.
 *
 * A segmented row is the right control for this — one period is in effect, and the row says "pick
 * one of these" rather than "toggle any of these". But its five segments share the width equally,
 * so each label gets a fifth of the screen: at large text sizes "All time" and "Month" have nowhere
 * to go but truncation, and a control whose options cannot be read is not a control.
 *
 * Past that point the same single choice moves into a menu, where a label gets the full width. The
 * choice, the options and the selection semantics are identical; only the shape changes.
 */
@Composable
internal fun PeriodSelector(selected: TrackingPeriod?, onSelectPeriod: (TrackingPeriod) -> Unit) {
    if (LocalDensity.current.fontScale >= LARGE_TEXT_SCALE) {
        PeriodMenu(current = selected, onSelectPeriod = onSelectPeriod)
    } else {
        PeriodSegments(selected = selected, onSelectPeriod = onSelectPeriod)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSegments(selected: TrackingPeriod?, onSelectPeriod: (TrackingPeriod) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        TrackingPeriod.entries.forEachIndexed { index, period ->
            SegmentedButton(
                selected = period == selected,
                onClick = { onSelectPeriod(period) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = TrackingPeriod.entries.size),
            ) {
                Text(periodLabel(period), maxLines = 1)
            }
        }
    }
}

/**
 * The same five periods at a text size the row cannot hold. Each entry carries radio-button
 * semantics inside a selectable group, so a screen reader still announces this as one choice out of
 * five with one in effect — which is what the segmented row was saying visually.
 */
@Composable
private fun PeriodMenu(current: TrackingPeriod?, onSelectPeriod: (TrackingPeriod) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val currentText = current?.let { periodLabel(it) } ?: stringResource(R.string.period_custom)
    // The button shows only the period, which on its own says nothing about what it controls.
    val label = stringResource(R.string.cd_period_selector, currentText)

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
        ) {
            Text(currentText, modifier = Modifier.weight(1f))
            Icon(imageVector = WalletIcons.Expand, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.selectableGroup(),
        ) {
            TrackingPeriod.entries.forEach { period ->
                PeriodMenuItem(period = period, isCurrent = period == current) {
                    expanded = false
                    onSelectPeriod(period)
                }
            }
        }
    }
}

@Composable
private fun PeriodMenuItem(period: TrackingPeriod, isCurrent: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(periodLabel(period)) },
        trailingIcon = {
            if (isCurrent) {
                Icon(imageVector = WalletIcons.Selected, contentDescription = null)
            }
        },
        onClick = onClick,
        modifier = Modifier.semantics {
            role = Role.RadioButton
            selected = isCurrent
        },
    )
}

@Composable
internal fun periodLabel(period: TrackingPeriod): String = stringResource(
    when (period) {
        TrackingPeriod.DAY -> R.string.period_day
        TrackingPeriod.WEEK -> R.string.period_week
        TrackingPeriod.MONTH -> R.string.period_month
        TrackingPeriod.YEAR -> R.string.period_year
        TrackingPeriod.ALL_TIME -> R.string.period_all_time
    },
)
