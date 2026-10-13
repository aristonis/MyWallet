package org.aristonis.mywallet

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.ui.AppRoot
import org.aristonis.mywallet.ui.AppViewModel
import org.aristonis.mywallet.ui.theme.MyWalletTheme

// AndroidX's default navigation scrims keep button contrast on API 28 and lower.
private val LIGHT_NAVIGATION_BAR_SCRIM = 0xE6FFFFFF.toInt()
private val DARK_NAVIGATION_BAR_SCRIM = 0x801B1B1B.toInt()

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
            val dark = !shouldUseDarkStatusBarIcons(theme, isSystemInDarkTheme())
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim = LIGHT_NAVIGATION_BAR_SCRIM,
                        darkScrim = DARK_NAVIGATION_BAR_SCRIM,
                        detectDarkMode = { dark },
                    ),
                )
            }
            MyWalletTheme(darkTheme = dark) {
                AppRoot(viewModel = appViewModel)
            }
        }
    }
}

internal fun shouldUseDarkStatusBarIcons(theme: ThemePreference, systemDark: Boolean): Boolean =
    when (theme) {
        ThemePreference.SYSTEM -> !systemDark
        ThemePreference.LIGHT -> true
        ThemePreference.DARK -> false
    }
