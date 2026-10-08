package org.aristonis.mywallet.ui.window

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.format.YEAR_SKELETON
import org.aristonis.mywallet.ui.format.rememberSkeletonFormatter
import org.aristonis.mywallet.ui.icons.WalletIcons
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

/** A standalone abbreviated month name ("Aug"), the form a month grid needs. */
private const val MONTH_CELL_SKELETON = "LLL"

private const val MONTH_GRID_COLUMNS = 3

/** How far either side of the shown year the year list reaches; decades of history, a little future. */
private const val YEARS_BEFORE = 100
private const val YEARS_AFTER = 20

/** Leaves a couple of earlier years above the current one, so the list opens with it in context. */
private const val YEAR_LIST_LEAD = 2

/**
 * The years every picker here offers: the calendar's own range, so the month stepper and the year
 * list never reach a year the day calendar refuses, or one too far out for java.time to build.
 */
@OptIn(ExperimentalMaterial3Api::class)
private val PICKER_YEARS: IntRange = DatePickerDefaults.YearRange

private val YEAR_LIST_MAX_HEIGHT = 320.dp
private val GRID_SPACING = 8.dp

/**
 * A picker sized to the period: a calendar for a day or a week (any day lands in its week), a month
 * grid for a month, a list for a year. Each is seeded from the window's anchor — a real date the
 * user is looking at — and reports one date inside the chosen period.
 */
@Composable
internal fun WindowPicker(window: TrackingWindow.Period, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    when (window.period) {
        TrackingPeriod.DAY, TrackingPeriod.WEEK -> DayPickerDialog(window.anchor, onPicked, onDismiss)
        TrackingPeriod.MONTH -> MonthPickerDialog(window.anchor, onPicked, onDismiss)
        TrackingPeriod.YEAR -> YearPickerDialog(window.anchor, onPicked, onDismiss)
        // All time is one span with nothing to choose inside it; the bar never offers this.
        TrackingPeriod.ALL_TIME -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(initial: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.toUtcMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPicked(utcMillisToDate(it)) } ?: onDismiss() },
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

/**
 * Twelve months under a year stepper, which starts on the shown year (pulled into [PICKER_YEARS]
 * should the window sit outside it). Material has no month picker, and a full calendar would ask
 * the user to choose a day they do not care about.
 */
@Composable
private fun MonthPickerDialog(initial: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    var year by rememberSaveable { mutableIntStateOf(initial.year.coerceIn(PICKER_YEARS)) }
    var month by rememberSaveable { mutableIntStateOf(initial.monthValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_month_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(GRID_SPACING)) {
                YearStepper(year = year, onYearChange = { year = it })
                MonthGrid(year = year, selected = month, onSelect = { month = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onPicked(YearMonth.of(year, month).atDay(1)) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Steps one year at a time and stops at each end of [PICKER_YEARS], disabling the arrow there. */
@Composable
private fun YearStepper(year: Int, onYearChange: (Int) -> Unit) {
    val formatYear = rememberSkeletonFormatter(YEAR_SKELETON)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onYearChange(year - 1) }, enabled = year > PICKER_YEARS.first) {
            Icon(imageVector = WalletIcons.Previous, contentDescription = stringResource(R.string.cd_previous_year))
        }
        Text(
            text = formatYear(LocalDate.of(year, 1, 1)),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onYearChange(year + 1) }, enabled = year < PICKER_YEARS.last) {
            Icon(imageVector = WalletIcons.Next, contentDescription = stringResource(R.string.cd_next_year))
        }
    }
}

/** Month names come from the locale, so they read in the user's language and calendar wording. */
@Composable
private fun MonthGrid(year: Int, selected: Int, onSelect: (Int) -> Unit) {
    val formatMonth = rememberSkeletonFormatter(MONTH_CELL_SKELETON)
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(GRID_SPACING)) {
        Month.entries.chunked(MONTH_GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GRID_SPACING)) {
                row.forEach { month ->
                    ChoiceCell(
                        text = formatMonth(LocalDate.of(year, month, 1)),
                        isSelected = month.value == selected,
                        onClick = { onSelect(month.value) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One option in a pick-one set: filled when chosen, so the choice never rests on colour alone. */
@Composable
private fun ChoiceCell(text: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val choice = modifier.semantics {
        role = Role.RadioButton
        selected = isSelected
    }
    val label: @Composable () -> Unit = { Text(text, textAlign = TextAlign.Center) }
    if (isSelected) {
        FilledTonalButton(onClick = onClick, modifier = choice) { label() }
    } else {
        TextButton(onClick = onClick, modifier = choice) { label() }
    }
}

/**
 * A tap on a year is the whole decision, so the list closes on it rather than asking to confirm. The
 * list reaches [YEARS_BEFORE] back and [YEARS_AFTER] ahead of the shown year, cut to [PICKER_YEARS].
 */
@Composable
private fun YearPickerDialog(initial: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val formatYear = rememberSkeletonFormatter(YEAR_SKELETON)
    val shown = initial.year.coerceIn(PICKER_YEARS)
    val years = maxOf(shown - YEARS_BEFORE, PICKER_YEARS.first)..minOf(shown + YEARS_AFTER, PICKER_YEARS.last)
    val leadIndex = (shown - years.first - YEAR_LIST_LEAD).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = leadIndex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_year_title)) },
        text = {
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = YEAR_LIST_MAX_HEIGHT).selectableGroup(),
            ) {
                items(years.toList(), key = { it }) { year ->
                    ChoiceCell(
                        text = formatYear(LocalDate.of(year, 1, 1)),
                        isSelected = year == initial.year,
                        onClick = { onPicked(LocalDate.of(year, 1, 1)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
