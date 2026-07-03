package org.aristonis.mywallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.aristonis.mywallet.ui.onboarding.OnboardingScreen
import org.aristonis.mywallet.ui.theme.MyWalletTheme

/**
 * [AndroidEntryPoint] lets Hilt inject into this activity and, transitively, lets `hiltViewModel()`
 * resolve ViewModels inside the Compose tree. For now it shows onboarding unconditionally — the
 * onboarding-vs-Home routing gate lands in SG-8 (see backlog.md).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyWalletTheme {
                OnboardingScreen()
            }
        }
    }
}
