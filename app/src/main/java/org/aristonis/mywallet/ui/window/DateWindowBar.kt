package org.aristonis.mywallet.ui.window

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.format.rememberWindowLabel
import org.aristonis.mywallet.ui.icons.WalletIcons
import java.time.LocalDate

/**
 * Tight spacing between the period choice and the dates it resolves to: the two are one control, so
 * they sit closer together than unrelated sections do.
 */
private val BAR_SPACING = 8.dp

/** The label is a touch target even when it sits alone in its row. */
private val MIN_TOUCH_TARGET = 48.dp

/**
 * Everything the date bar can ask of its screen. Every callback is required, so a screen that forgets
 * one fails to compile instead of shipping a button that silently does nothing. Tests and previews
 * start from [None] and `copy` in only the callbacks they care about.
 */
data class DateWindowActions(
    val onSelectPeriod: (TrackingPeriod) -> Unit,
    val onStep: (Long) -> Unit,
    val onJumpTo: (LocalDate) -> Unit,
    val onSelectRange: (LocalDate, LocalDate) -> Unit,
    val onClearRange: () -> Unit,
) {
    companion object {
        /** Ignores every request; for previews and tests, never for a real screen. */
        val None = DateWindowActions(
            onSelectPeriod = {},
            onStep = {},
            onJumpTo = {},
            onSelectRange = { _, _ -> },
            onClearRange = {},
        )
    }
}

/**
 * Which dates a screen covers, and the ways to change them: the period kind, then the period itself
 * with arrows to its neighbours and a tap on its name to jump anywhere. A custom range has no
 * neighbours, so it trades the arrows for a way back out.
 *
 * Shared by Tracking and Transactions so the two screens can never describe the same dates
 * differently.
 */
@Composable
fun DateWindowBar(window: TrackingWindow, actions: DateWindowActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BAR_SPACING)) {
        PeriodSelector(
            selected = (window as? TrackingWindow.Period)?.period,
            onSelectPeriod = actions.onSelectPeriod,
        )
        WindowNavigator(window = window, actions = actions)
    }
}

/**
 * `‹  August 2026  ›`. The arrows come first and last in the row rather than left and right, so a
 * right-to-left layout puts "earlier" on the right, where its reader expects the past to be.
 */
@Composable
private fun WindowNavigator(window: TrackingWindow, actions: DateWindowActions) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val pickable = window as? TrackingWindow.Period
    val canPick = pickable != null && pickable.canStep

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (window.canStep) {
            StepButton(WalletIcons.Previous, stringResource(R.string.cd_previous_period)) { actions.onStep(-1) }
        }
        WindowLabel(
            window = window,
            onClick = if (canPick) ({ picking = true }) else null,
            clickLabel = pickable?.let { pickActionLabel(it.period) },
        )
        if (window.canStep) {
            StepButton(WalletIcons.Next, stringResource(R.string.cd_next_period)) { actions.onStep(1) }
        }
        if (window is TrackingWindow.Custom) {
            StepButton(WalletIcons.Close, stringResource(R.string.cd_clear_date_range), actions.onClearRange)
        }
    }

    if (picking && pickable != null) {
        WindowPicker(
            window = pickable,
            onPicked = { date ->
                picking = false
                actions.onJumpTo(date)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

/**
 * The dates in words. It takes whatever width the buttons leave and wraps rather than truncating, so
 * at large text the reader still sees which dates they are looking at.
 *
 * When it opens a picker it is drawn like a text button, but built on a plain click so it can carry
 * [clickLabel]: "double-tap to choose a month" tells a screen reader user what the tap does, where a
 * bare button would only repeat the dates. A label that opens nothing has no click and no label.
 */
@Composable
private fun RowScope.WindowLabel(window: TrackingWindow, onClick: (() -> Unit)?, @StringRes clickLabel: Int?) {
    val text = rememberWindowLabel(window) ?: stringResource(R.string.period_all_time)
    val base = Modifier.weight(1f).heightIn(min = MIN_TOUCH_TARGET)
    if (onClick == null || clickLabel == null) {
        Row(modifier = base, verticalAlignment = Alignment.CenterVertically) {
            LabelText(text, Color.Unspecified)
        }
        return
    }
    val actionLabel = stringResource(clickLabel)
    Row(
        modifier = base
            .clip(ButtonDefaults.textShape)
            .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onClick)
            .padding(ButtonDefaults.TextButtonContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelText(text, MaterialTheme.colorScheme.primary)
    }
}

/** What tapping the label lets the user choose, named by the period the picker offers. */
@StringRes
private fun pickActionLabel(period: TrackingPeriod): Int? = when (period) {
    TrackingPeriod.DAY -> R.string.action_choose_day
    TrackingPeriod.WEEK -> R.string.action_choose_week
    TrackingPeriod.MONTH -> R.string.action_choose_month
    TrackingPeriod.YEAR -> R.string.action_choose_year
    // All time has nothing to pick inside it, so its label is never tappable.
    TrackingPeriod.ALL_TIME -> null
}

@Composable
private fun RowScope.LabelText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(1f),
    )
}
