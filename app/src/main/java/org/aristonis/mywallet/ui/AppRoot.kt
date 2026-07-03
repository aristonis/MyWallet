package org.aristonis.mywallet.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.ui.home.HomeScreen
import org.aristonis.mywallet.ui.onboarding.OnboardingScreen

/**
 * Top-level routing gate: shows onboarding until settings exist, then Home. Because it observes the
 * settings signal, finishing onboarding (which writes settings) automatically swaps to Home, and a
 * relaunch on an already-set-up device skips onboarding entirely.
 */
@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val destination by viewModel.startDestination.collectAsStateWithLifecycle()
    when (destination) {
        StartDestination.LOADING -> LoadingScreen()
        StartDestination.ONBOARDING -> OnboardingScreen()
        StartDestination.HOME -> HomeScreen()
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
