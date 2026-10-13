package org.aristonis.mywallet.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.icons.WalletIcons

/** The Tracking overflow exports the visible dates after the report is readable. */
@Composable
internal fun TrackingExportMenu(enabled: Boolean, onExport: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(WalletIcons.More, contentDescription = stringResource(R.string.tracking_more_options))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tracking_export_csv)) },
                enabled = enabled,
                onClick = { expanded = false; onExport() },
            )
        }
    }
}
