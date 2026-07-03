package org.aristonis.mywallet.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.ui.account.AddAccountScreen
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
        StartDestination.HOME -> HomeFlow()
    }
}

/**
 * Lightweight Home <-> Add-account navigation. With only two screens, a `remember`ed flag beats
 * pulling in a nav library; when a bottom-nav shell arrives this becomes a real NavHost.
 */
@Composable
private fun HomeFlow() {
    var showAddAccount by remember { mutableStateOf(false) }
    if (showAddAccount) {
        AddAccountScreen(onDone = { showAddAccount = false })
    } else {
        HomeScreen(onAddAccount = { showAddAccount = true })
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
