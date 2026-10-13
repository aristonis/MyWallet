package org.aristonis.mywallet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.WalletTopAppBar

@Composable
fun AppLicenseScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val notice = remember(context) { context.assets.open("NOTICE").bufferedReader().use { it.readText() } }
    val license = remember(context) { context.assets.open("LICENSE").bufferedReader().use { it.readText() } }

    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.identity_app_license_title), onBack = onDone)
        },
    ) { innerPadding ->
        SelectionContainer {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.identity_app_license_notice), style = MaterialTheme.typography.titleMedium)
                Text(notice, style = MaterialTheme.typography.bodyMedium)
                Text(license, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
