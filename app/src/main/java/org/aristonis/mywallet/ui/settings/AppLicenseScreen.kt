package org.aristonis.mywallet.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.theme.WalletTheme
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.io.IOException

@Composable
fun AppLicenseScreen(
    onDone: () -> Unit,
    readAsset: (Context, String) -> String = ::readBundledAsset,
) {
    val context = LocalContext.current
    val document = remember(context, readAsset) {
        try {
            val notice = readAsset(context, "NOTICE")
            val license = readAsset(context, "LICENSE")
            if (notice.isBlank() || license.isBlank()) LicenseDocument.Unavailable
            else LicenseDocument.Ready(notice, license)
        } catch (e: IOException) {
            LicenseDocument.Unavailable
        } catch (e: SecurityException) {
            LicenseDocument.Unavailable
        }
    }
    val dimensions = WalletTheme.dimensions

    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.identity_app_license_title), onBack = onDone)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(dimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.sectionGap),
        ) {
            when (document) {
                LicenseDocument.Unavailable -> Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        stringResource(R.string.w2_license_unavailable),
                        modifier = Modifier.padding(dimensions.cardPadding),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                is LicenseDocument.Ready -> SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(dimensions.sectionGap)) {
                        Text(
                            stringResource(R.string.identity_app_license_notice),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(document.notice, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.w2_project_license_heading),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(document.license, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun readBundledAsset(context: Context, name: String): String =
    context.assets.open(name).bufferedReader().use { it.readText() }

private sealed interface LicenseDocument {
    data class Ready(val notice: String, val license: String) : LicenseDocument
    data object Unavailable : LicenseDocument
}

@Preview(name = "License read failure light", showBackground = true, widthDp = 360)
@Preview(name = "License read failure dark", showBackground = true, widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LicenseReadFailurePreview() {
    MyWalletTheme(dynamicColor = false) {
        AppLicenseScreen(onDone = {}, readAsset = { _, _ -> throw IOException("unavailable") })
    }
}
