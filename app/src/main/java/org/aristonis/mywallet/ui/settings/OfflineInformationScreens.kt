package org.aristonis.mywallet.ui.settings

import android.content.res.Configuration
import androidx.annotation.StringRes
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme

/** Static guidance ships with the app so it stays available without a network connection. */
@Composable
fun PrivacyInformationScreen(onDone: () -> Unit) {
    InformationPage(title = R.string.identity_privacy_title, onDone = onDone) {
        InformationSection(R.string.identity_privacy_storage_heading, R.string.identity_privacy_storage)
        InformationSection(R.string.identity_privacy_export_heading, R.string.identity_privacy_export)
        InformationSection(R.string.identity_privacy_restore_heading, R.string.identity_privacy_restore)
    }
}

@Composable
fun SupportFaqScreen(onDone: () -> Unit) {
    InformationPage(title = R.string.identity_support_title, onDone = onDone) {
        InformationSection(R.string.identity_support_start_heading, R.string.identity_support_start)
        InformationSection(R.string.identity_support_backup_heading, R.string.identity_support_backup)
        InformationSection(R.string.identity_support_report_heading, R.string.identity_support_report)
    }
}

@Composable
internal fun InformationPage(
    @StringRes title: Int,
    onDone: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dimensions = WalletTheme.dimensions
    Scaffold(topBar = { WalletTopAppBar(title = stringResource(title), onBack = onDone) }) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(dimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.sectionGap),
        ) {
            content()
        }
    }
}

@Composable
internal fun InformationSection(@StringRes heading: Int, @StringRes body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap)) {
        Text(stringResource(heading), style = MaterialTheme.typography.titleMedium)
        val linkStyle = TextLinkStyles(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            ),
        )
        SelectionContainer {
            Text(
                linkedInformationText(stringResource(body), linkStyle),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Preserves information text while annotating its web and email addresses as tappable links. */
internal fun linkedInformationText(body: String, linkStyle: TextLinkStyles): AnnotatedString =
    buildAnnotatedString {
        append(body)
        findUrls(body).forEach { range ->
            addLink(
                url = LinkAnnotation.Url(body.substring(range), linkStyle),
                start = range.first,
                end = range.last + 1,
            )
        }
        EMAIL_PATTERN.findAll(body).forEach { match ->
            addLink(
                url = LinkAnnotation.Url("mailto:${match.value}", linkStyle),
                start = match.range.first,
                end = match.range.last + 1,
            )
        }
    }

@Preview(name = "Privacy light", showBackground = true, widthDp = 360)
@Preview(name = "Privacy dark", showBackground = true, widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PrivacyPreview() {
    MyWalletTheme(dynamicColor = false) { PrivacyInformationScreen(onDone = {}) }
}

@Preview(name = "Support light", showBackground = true, widthDp = 360)
@Preview(name = "Support dark", showBackground = true, widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SupportPreview() {
    MyWalletTheme(dynamicColor = false) { SupportFaqScreen(onDone = {}) }
}

private val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
