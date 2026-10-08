package org.aristonis.mywallet.ui.window

import androidx.lifecycle.SavedStateHandle
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Keeps a [TrackingWindow] across process death as plain primitives.
 *
 * The domain types stay free of Parcelable and Serializable: those are Android and JVM concerns, and
 * the domain module knows neither. A window is small enough that a handful of keys describe it
 * fully, so a saver here costs less than teaching the domain how to be bundled.
 */

private const val KIND_PERIOD = "period"
private const val KIND_CUSTOM = "custom"

private fun String.kindKey() = "$this.kind"
private fun String.periodKey() = "$this.period"
private fun String.anchorKey() = "$this.anchor"
private fun String.startKey() = "$this.start"
private fun String.endKey() = "$this.end"

/** Every epoch day java.time can name; anything outside it is not a date at all. */
private val CALENDAR_EPOCH_DAYS = LocalDate.MIN.toEpochDay()..LocalDate.MAX.toEpochDay()

/** Stores [window] under [key], replacing whatever window was there before. */
fun SavedStateHandle.writeWindow(key: String, window: TrackingWindow) {
    when (window) {
        is TrackingWindow.Period -> {
            this[key.kindKey()] = KIND_PERIOD
            this[key.periodKey()] = window.period.name
            this[key.anchorKey()] = window.anchor.toEpochDay()
        }
        is TrackingWindow.Custom -> {
            this[key.kindKey()] = KIND_CUSTOM
            this[key.startKey()] = window.range.start.toEpochDay()
            this[key.endKey()] = window.range.endInclusive.toEpochDay()
        }
    }
}

/**
 * The window stored under [key], or null when none was stored or what was stored no longer reads as
 * one (a period renamed between app versions, a day outside the calendar, a reversed range). Null
 * lets the caller start from its default instead of crashing on state the user never sees.
 */
fun SavedStateHandle.readWindow(key: String): TrackingWindow? = when (get<String>(key.kindKey())) {
    KIND_PERIOD -> readPeriod(key)
    KIND_CUSTOM -> readCustom(key)
    else -> null
}

private fun SavedStateHandle.readPeriod(key: String): TrackingWindow? {
    val name = get<String>(key.periodKey()) ?: return null
    val period = TrackingPeriod.entries.firstOrNull { it.name == name } ?: return null
    val anchor = get<Long>(key.anchorKey())?.takeIf { it in CALENDAR_EPOCH_DAYS } ?: return null
    // A real anchor can still sit in a week or month that runs past the calendar's last day.
    return orNullIfInvalid { TrackingWindow.Period(period, LocalDate.ofEpochDay(anchor)) }
}

private fun SavedStateHandle.readCustom(key: String): TrackingWindow? {
    val start = get<Long>(key.startKey())?.takeIf { it in CALENDAR_EPOCH_DAYS } ?: return null
    val end = get<Long>(key.endKey())?.takeIf { it in CALENDAR_EPOCH_DAYS } ?: return null
    if (start > end) return null
    return orNullIfInvalid {
        TrackingWindow.Custom(DateRange(LocalDate.ofEpochDay(start), LocalDate.ofEpochDay(end)))
    }
}

/**
 * Builds a window, or null when the domain rejects it. Only the two failures a bad date can raise are
 * caught; anything else is a real bug and should still surface.
 */
private inline fun orNullIfInvalid(build: () -> TrackingWindow): TrackingWindow? = try {
    build()
} catch (e: DateTimeException) {
    null
} catch (e: IllegalArgumentException) {
    null
}
