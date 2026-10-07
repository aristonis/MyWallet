package org.aristonis.mywallet.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.time.LocalDate

/** The theme choices, in display order, each paired with the label resource the user sees. */
private val themeOptions = listOf(
    ThemePreference.SYSTEM to R.string.theme_system,
    ThemePreference.LIGHT to R.string.theme_light,
    ThemePreference.DARK to R.string.theme_dark,
)

/**
 * Settings, as grouped sections rather than a page of buttons: each row says what it is and what it
 * is currently set to, so most questions are answered without opening anything.
 *
 * The Storage Access Framework pickers live here because they need the Activity result registry; the
 * view-model only ever sees the resulting text.
 */
@Composable
fun SettingsScreen(
    onManageAccounts: () -> Unit,
    onManageCategories: () -> Unit,
    onManageRates: () -> Unit,
    onChangeBaseCurrency: () -> Unit,
    onAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val baseCurrency by viewModel.baseCurrencyCode.collectAsStateWithLifecycle()

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
        currentTheme = theme,
        baseCurrencyCode = baseCurrency,
        onThemeSelected = viewModel::selectTheme,
        onExport = { exportLauncher.launch("mywallet-backup-${LocalDate.now()}.json") },
        onImport = { importLauncher.launch(arrayOf("*/*")) },
        onStatusShown = viewModel::acknowledge,
        onManageAccounts = onManageAccounts,
        onManageCategories = onManageCategories,
        onManageRates = onManageRates,
        onChangeBaseCurrency = onChangeBaseCurrency,
        onAbout = onAbout,
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
    currentTheme: ThemePreference,
    baseCurrencyCode: String?,
    onThemeSelected: (ThemePreference) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onStatusShown: () -> Unit,
    onManageAccounts: () -> Unit,
    onManageCategories: () -> Unit,
    onManageRates: () -> Unit,
    onChangeBaseCurrency: () -> Unit,
    onAbout: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val isWorking = state.status is BackupStatus.Working
    var pickingTheme by rememberSaveable { mutableStateOf(false) }

    // Surface a terminal Success/Error once, then hand control back so the state returns to Idle.
    val outcome = when (val status = state.status) {
        is BackupStatus.Success -> status.message
        is BackupStatus.Error -> status.message
        else -> null
    }
    // Resolved here, in composition: showSnackbar runs in a coroutine, where stringResource cannot.
    val message = outcome?.text()
    LaunchedEffect(state.status) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onStatusShown()
        }
    }

    Scaffold(
        topBar = { WalletTopAppBar(title = stringResource(R.string.settings_title)) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            // Settings is a stack of fixed-height rows, so it runs off a compact screen long before
            // 200% font scale — at which point the last section becomes unreachable, not just cramped.
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_wallet)) {
                SettingsRow(
                    label = stringResource(R.string.settings_base_currency),
                    value = baseCurrencyCode,
                    icon = WalletIcons.Rates,
                    onClick = onChangeBaseCurrency,
                )
                SettingsRow(
                    label = stringResource(R.string.settings_accounts),
                    icon = WalletIcons.AccountDefault,
                    onClick = onManageAccounts,
                )
                SettingsRow(
                    label = stringResource(R.string.settings_categories),
                    icon = WalletIcons.Categories,
                    onClick = onManageCategories,
                )
            }

            // Rates are reachable from Home too, but that route is the missing-rate warning asking to
            // be fixed. This one is for changing a rate nothing is currently complaining about.
            SettingsSection(title = stringResource(R.string.settings_section_currencies)) {
                SettingsRow(
                    label = stringResource(R.string.settings_rates),
                    icon = WalletIcons.Rates,
                    onClick = onManageRates,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_data)) {
                SettingsRow(
                    label = stringResource(R.string.settings_export),
                    supporting = stringResource(R.string.settings_export_body),
                    icon = WalletIcons.Backup,
                    onClick = { if (!isWorking) onExport() },
                )
                SettingsRow(
                    label = stringResource(R.string.settings_restore),
                    // Kept because it is the one action here that cannot be taken back.
                    supporting = stringResource(R.string.settings_restore_body),
                    icon = WalletIcons.Restore,
                    onClick = { if (!isWorking) onImport() },
                )
                if (isWorking) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.settings_working), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                SettingsRow(
                    label = stringResource(R.string.settings_theme),
                    value = stringResource(currentTheme.labelRes()),
                    icon = WalletIcons.Appearance,
                    onClick = { pickingTheme = true },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                SettingsRow(
                    label = stringResource(R.string.settings_about),
                    icon = WalletIcons.About,
                    onClick = onAbout,
                )
            }
        }
    }

    if (pickingTheme) {
        ThemePickerSheet(
            currentTheme = currentTheme,
            onThemeSelected = {
                onThemeSelected(it)
                pickingTheme = false
            },
            onDismiss = { pickingTheme = false },
        )
    }
}

/** A titled group of rows. The title is quiet and small; the rows are what the user is scanning. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        SectionHeader(
            title = title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        content()
    }
}

private fun ThemePreference.labelRes(): Int = themeOptions.first { it.first == this }.second

/**
 * Three choices is a short list, so it opens as a sheet rather than taking over the screen. The rows
 * are `selectable` with a radio role, which is what makes a screen reader announce them as a group of
 * choices with one selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemePickerSheet(
    currentTheme: ThemePreference,
    onThemeSelected: (ThemePreference) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            themeOptions.forEach { (preference, labelRes) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = preference == currentTheme,
                            role = Role.RadioButton,
                            onClick = { onThemeSelected(preference) },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // The row carries the click and the announcement, so the button itself is inert.
                    RadioButton(selected = preference == currentTheme, onClick = null)
                    Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
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
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        text = { Text(stringResource(R.string.restore_confirm_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_replace)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    MyWalletTheme(dynamicColor = false) {
        SettingsContent(
            state = BackupUiState(),
            currentTheme = ThemePreference.SYSTEM,
            baseCurrencyCode = "USD",
            onThemeSelected = {},
            onExport = {},
            onImport = {},
            onStatusShown = {},
            onManageAccounts = {},
            onManageCategories = {},
            onManageRates = {},
            onChangeBaseCurrency = {},
            onAbout = {},
        )
    }
}
