package org.aristonis.mywallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.aristonis.mywallet.ui.AppRoot
import org.aristonis.mywallet.ui.theme.MyWalletTheme

/**
 * [AndroidEntryPoint] lets Hilt inject into this activity and, transitively, lets `hiltViewModel()`
 * resolve ViewModels inside the Compose tree. [AppRoot] decides onboarding-vs-Home from the settings
 * signal.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyWalletTheme {
                AppRoot()
            }
        }
    }
}
