package org.aristonis.mywallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.ui.AppRoot
import org.aristonis.mywallet.ui.AppViewModel
import org.aristonis.mywallet.ui.theme.MyWalletTheme

/**
 * [AndroidEntryPoint] lets Hilt inject into this activity and, transitively, lets `hiltViewModel()`
 * resolve ViewModels inside the Compose tree. [AppRoot] decides onboarding-vs-Home from the settings
 * signal.
 *
 * The saved theme is read here, above [MyWalletTheme], because the choice reskins the whole tree.
 * It's a live [androidx.compose.runtime.State] backed by the settings flow, so changing the setting
 * recomposes with the new colours without a restart. LIGHT/DARK force the scheme; SYSTEM defers to
 * `isSystemInDarkTheme()`, which must be read inside the composable scope.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val theme by appViewModel.theme.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            MyWalletTheme(darkTheme = dark) {
                AppRoot(viewModel = appViewModel)
            }
        }
    }
}
