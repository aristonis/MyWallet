package org.aristonis.mywallet.ui.navigation

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/** Debug-only host for production navigation tests; it bypasses onboarding without writing wallet data. */
@AndroidEntryPoint
class IdentityNavigationHostActivity : ComponentActivity()
