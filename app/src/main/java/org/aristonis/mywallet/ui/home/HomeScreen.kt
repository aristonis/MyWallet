package org.aristonis.mywallet.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Placeholder Home. The real net-worth hero + account cards + FAB land in the next SG-8 slice; this
 * exists so the routing gate ([org.aristonis.mywallet.ui.AppRoot]) has a post-onboarding destination.
 */
@Composable
fun HomeScreen() {
    Scaffold { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text("Home — accounts & net worth (coming in the next SG-8 slice)")
        }
    }
}
