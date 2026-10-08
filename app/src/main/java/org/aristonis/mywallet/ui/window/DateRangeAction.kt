package org.aristonis.mywallet.ui.window

import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.icons.WalletIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The top-bar way into a custom range. It lives in the app bar rather than the date bar because
 * choosing arbitrary dates is the exception: the period and its arrows cover the everyday case and
 * keep the date bar to one line of controls.
 */
@Composable
fun DateRangeAction(window: TrackingWindow, onSelectRange: (LocalDate, LocalDate) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { picking = true }) {
        Icon(imageVector = WalletIcons.DateRange, contentDescription = stringResource(R.string.cd_choose_dates))
    }
    if (picking) {
        DateRangeDialog(
            window = window,
            onPicked = { start, end ->
                picking = false
                onSelectRange(start, end)
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Opens on the dates already shown, so adjusting a month by a few days starts from that month. The
 * all-time range is not seeded: its bounds are sentinels that overflow epoch millis.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(
    window: TrackingWindow,
    onPicked: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val seed = window.range.takeUnless { it.isAllTime }
    val seedStart = seed?.start?.toUtcMillis()
    val seedEnd = seed?.endInclusive?.toUtcMillis()
    // Seed both ends or neither: a lone end the user did not choose would read as half a decision.
    val seeded = seedStart != null && seedEnd != null
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = seedStart.takeIf { seeded },
        initialSelectedEndDateMillis = seedEnd.takeIf { seeded },
    )
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (start != null && end != null) onPicked(utcMillisToDate(start), utcMillisToDate(end)) },
                // A range needs both ends; confirming half of one would have nothing to cover.
                enabled = start != null && end != null,
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}

/*
 * Material's pickers speak UTC-midnight millis. Converting in UTC both ways keeps the calendar day
 * fixed; the device zone would shift it by one anywhere west of UTC.
 */

/**
 * This day as a picker seed, or null when the picker's calendar does not reach it. Arrows can step a
 * window past the years the picker shows, and seeding one outside them is rejected, so such a day
 * simply opens on no selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun LocalDate.toUtcMillis(): Long? =
    if (year in DatePickerDefaults.YearRange) atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else null

internal fun utcMillisToDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
