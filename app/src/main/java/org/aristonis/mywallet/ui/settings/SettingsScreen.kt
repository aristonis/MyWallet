package org.aristonis.mywallet.ui.settings

// UI copy hardcoded; localizing strings (RTL/i18n) comes later.

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.time.LocalDate

/**
 * Settings screen. Today it only hosts backup & restore. [onDone] returns to Home (Done button and
 * system back). No nav library: the parent toggles this screen, same as the other secondary screens.
 * The Storage Access Framework pickers live here because they need the Activity result registry; the
 * view-model only ever sees the resulting text.
 */
@Composable
fun SettingsScreen(
    onDone: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)

    // Export writes a real application/json document. Import accepts "*/*" on purpose: many file
    // providers report a .json as octet-stream or text/plain and would hide it under a strict filter,
    // so the user couldn't pick the backup they just saved. The codec still validates the contents,
    // so a non-backup fails loud.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTo) }

    // Restore is destructive, so a picked file isn't applied straight away — it waits behind a
    // confirmation dialog. Saved across recreation so a rotation mid-confirm doesn't drop the dialog.
    var pendingRestoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingRestoreUri = uri }

    SettingsContent(
        state = state,
        onExport = { exportLauncher.launch("mywallet-backup-${LocalDate.now()}.json") },
        onImport = { importLauncher.launch(arrayOf("*/*")) },
        onStatusShown = viewModel::acknowledge,
        onDone = onDone,
    )

    pendingRestoreUri?.let { uri ->
        RestoreConfirmDialog(
            onConfirm = {
                pendingRestoreUri = null
                viewModel.importFrom(uri)
            },
            onDismiss = { pendingRestoreUri = null },
        )
    }
}

@Composable
private fun SettingsContent(
    state: BackupUiState,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onStatusShown: () -> Unit,
    onDone: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val isWorking = state.status is BackupStatus.Working

    // Surface a terminal Success/Error once, then hand control back so the state returns to Idle.
    val message = when (val status = state.status) {
        is BackupStatus.Success -> status.message
        is BackupStatus.Error -> status.message
        else -> null
    }
    LaunchedEffect(state.status) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onStatusShown()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("Done") }
            }

            Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
            Text(
                "Save all your data to a file, or replace it with a previously saved backup.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(onClick = onExport, enabled = !isWorking, modifier = Modifier.fillMaxWidth()) {
                Text("Export backup")
            }
            OutlinedButton(onClick = onImport, enabled = !isWorking, modifier = Modifier.fillMaxWidth()) {
                Text("Restore from backup")
            }

            if (isWorking) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Working…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun RestoreConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore backup?") },
        text = { Text("Replace all current data with this backup? This can't be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Replace") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    MyWalletTheme(dynamicColor = false) {
        SettingsContent(
            state = BackupUiState(),
            onExport = {},
            onImport = {},
            onStatusShown = {},
            onDone = {},
        )
    }
}
