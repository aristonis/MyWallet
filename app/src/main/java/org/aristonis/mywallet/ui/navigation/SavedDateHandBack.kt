package org.aristonis.mywallet.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.filterNotNull
import java.time.LocalDate

/**
 * Where an editor leaves the date of the entry it just saved, on the back-stack entry of the screen
 * it returns to. Stored as an epoch day: a plain Long is what a saved-state bundle keeps without help.
 */
internal const val SAVED_DATE_KEY = "savedDate"

/**
 * Leaves the editor, telling the Transactions list which date the saved entry landed on. The date is
 * written before popping so it is already waiting when the list comes back into view.
 *
 * It goes to the list's own back-stack entry, not to the screen underneath: the editor can be opened
 * from the list or from a read-only page above the list, and only the list listens. Only the
 * Transactions graph has a list that may be narrowed to some dates and says so when an entry lands
 * outside them. Opened from anywhere else there is no such entry on the back stack, so the editor
 * only pops and no date is left in a screen's saved state for whatever is shown there later.
 */
internal fun NavHostController.returnSavedDate(date: LocalDate) {
    runCatching { getBackStackEntry(WalletTab.TRANSACTIONS.rootRoute) }.getOrNull()
        ?.savedStateHandle
        ?.set(SAVED_DATE_KEY, date.toEpochDay())
    popBackStack()
}

/**
 * Hears each date an editor hands back to [entry] and passes it to [onSaved] once.
 *
 * It reads [NavBackStackEntry.savedStateHandle] here, in the composable, because a view model's
 * injected SavedStateHandle is a different handle from the entry's: a value the editor writes to the
 * entry never reaches it. The view model is told the date instead.
 *
 * The date is cleared from the handle before [onSaved] runs. The entry's handle outlives rotation and
 * process death, so a date left in it would be heard again by the rebuilt screen. Clearing (setting
 * null) rather than removing keeps the flow read here alive: removing a key also detaches its flow,
 * and a list that stays on screen under its editor (a dialog, a sheet) would then miss every later
 * date.
 */
@Composable
internal fun SavedDateEffect(entry: NavBackStackEntry, onSaved: (LocalDate) -> Unit) {
    val currentOnSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(entry) {
        val handle = entry.savedStateHandle
        handle.getStateFlow<Long?>(SAVED_DATE_KEY, null).filterNotNull().collect { epochDay ->
            handle[SAVED_DATE_KEY] = null
            currentOnSaved(LocalDate.ofEpochDay(epochDay))
        }
    }
}


private const val SAVED_ENTRY_KEY = "savedEntry"

internal fun NavHostController.returnSavedEntry(id: Long, date: LocalDate) {
    runCatching { getBackStackEntry(WalletTab.TRANSACTIONS.rootRoute) }.getOrNull()
        ?.savedStateHandle?.set(SAVED_ENTRY_KEY, longArrayOf(id, date.toEpochDay()))
    popBackStack()
}

@Composable
internal fun SavedEntryEffect(entry: NavBackStackEntry, onSaved: (Long, LocalDate) -> Unit) {
    val currentOnSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(entry) {
        val handle = entry.savedStateHandle
        handle.getStateFlow<LongArray?>(SAVED_ENTRY_KEY, null).filterNotNull().collect { saved ->
            handle[SAVED_ENTRY_KEY] = null
            if (saved.size == 2) currentOnSaved(saved[0], LocalDate.ofEpochDay(saved[1]))
        }
    }
}
