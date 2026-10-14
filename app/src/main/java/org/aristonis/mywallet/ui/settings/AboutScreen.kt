package org.aristonis.mywallet.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.aristonis.mywallet.BuildConfig
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme

/**
 * What this app is and which build of it you are looking at.
 *
 * The version is the point: it is the first thing anyone needs when reporting something that went
 * wrong, and asking a user to find it in the system settings is asking them to give up.
 */
@Composable
fun AboutScreen(
    onDone: () -> Unit,
    onLicense: () -> Unit,
    onPrivacy: () -> Unit,
    onSupport: () -> Unit,
) {
    val dimensions = WalletTheme.dimensions
    Scaffold(
        topBar = { WalletTopAppBar(title = stringResource(R.string.w2_about_title), onBack = onDone) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(dimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.regionGap),
        ) {
            Text(stringResource(R.string.identity_brand), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.identity_about_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.identity_about_export), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.w2_information),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = dimensions.relatedGap),
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.identity_privacy_title),
                        supporting = stringResource(R.string.w2_about_privacy_summary),
                        icon = WalletIcons.About,
                        onClick = onPrivacy,
                    )
                    SettingsRow(
                        label = stringResource(R.string.identity_support_title),
                        supporting = stringResource(R.string.w2_about_support_summary),
                        icon = WalletIcons.About,
                        onClick = onSupport,
                    )
                    SettingsRow(
                        label = stringResource(R.string.identity_app_license_action),
                        supporting = stringResource(R.string.w2_about_license_summary),
                        icon = WalletIcons.About,
                        onClick = onLicense,
                    )
                }
            }
        }
    }
}

@Preview(name = "About light", showBackground = true, widthDp = 360)
@Preview(name = "About dark", showBackground = true, widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "About large text", showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun AboutPreview() {
    MyWalletTheme(dynamicColor = false) {
        AboutScreen(onDone = {}, onLicense = {}, onPrivacy = {}, onSupport = {})
    }
}
