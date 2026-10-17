package org.aristonis.mywallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.ui.navigation.WalletNavHost
import org.aristonis.mywallet.ui.onboarding.OnboardingScreen

/**
 * Top-level routing gate: shows onboarding until settings exist, then the tabbed shell. Because it
 * observes the settings signal, finishing onboarding (which writes settings) automatically swaps to
 * the shell, and a relaunch on an already-set-up device skips onboarding entirely.
 *
 * Onboarding sits outside the navigation graph on purpose: it is a one-time gate with no tabs, no
 * back stack of its own, and nothing to return to.
 */
@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val destination by viewModel.startDestination.collectAsStateWithLifecycle()
    AppRootContent(destination)
}

@Composable
internal fun AppRootContent(destination: StartDestination) {
    when (destination) {
        // The starting window stays visible until the settings choose a destination.
        StartDestination.LOADING -> Unit
        StartDestination.ONBOARDING -> OnboardingScreen()
        StartDestination.HOME -> WalletNavHost()
    }
}
