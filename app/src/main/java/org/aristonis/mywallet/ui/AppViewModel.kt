package org.aristonis.mywallet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.port.SettingsRepository
import javax.inject.Inject

/** Which top-level screen to show, derived from whether onboarding has run. */
enum class StartDestination { LOADING, ONBOARDING, HOME }

/**
 * Roots the app: watches whether settings exist (the "is-onboarded?" signal) and picks the start
 * destination. Reactive — completing onboarding writes settings, which flips this to HOME. Starts at
 * LOADING until the first emission, so the UI never flashes the wrong screen.
 *
 * Also surfaces the saved [theme] so [MainActivity] can drive `MyWalletTheme` from it, above the
 * whole app tree. It lives here (not deeper in the screen) because the choice reskins everything, and
 * because it observes the same live settings flow — changing it recomposes the app without a restart.
 * Defaults to SYSTEM before settings exist (onboarding), matching a fresh install.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    settings: SettingsRepository,
) : ViewModel() {
    val startDestination: StateFlow<StartDestination> =
        settings.observeOrNull()
            .map { if (it == null) StartDestination.ONBOARDING else StartDestination.HOME }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartDestination.LOADING)

    val theme: StateFlow<ThemePreference> =
        settings.observeOrNull()
            .map { it?.theme ?: ThemePreference.SYSTEM }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemePreference.SYSTEM)
}
