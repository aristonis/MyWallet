package org.aristonis.mywallet

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.ui.AppRoot
import org.aristonis.mywallet.ui.AppViewModel
import org.aristonis.mywallet.ui.StartDestination
import org.aristonis.mywallet.ui.format.AppLocale
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.util.Locale

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
 * recomposes with the new colours. Android 12+ also persists splash appearance and may recreate the
 * Activity when application night mode changes. LIGHT/DARK force the scheme; SYSTEM defers to
 * `isSystemInDarkTheme()`, which must be read inside the composable scope.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        val incoming = newBase.resources.configuration
        val locale = AppLocale.from(incoming.locales[0])
        val configuration = Configuration(incoming).apply { setLocale(locale) }
        Locale.setDefault(locale)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition {
            appViewModel.startDestination.value == StartDestination.LOADING
        }
        observeSplashNightMode()
        enableEdgeToEdge()
        setContent {
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

    private fun observeSplashNightMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val modeManager = getSystemService(UiModeManager::class.java)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appViewModel.loadedTheme.collect { theme ->
                    appViewModel.splashNightMode.apply(theme, Build.VERSION.SDK_INT) { mode ->
                        modeManager.setApplicationNightMode(mode)
                    }
                }
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
