package org.aristonis.mywallet.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme
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
                        LicenseContent(formatLicenseText(document.notice))
                        Text(
                            stringResource(R.string.w2_project_license_heading),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LicenseContent(formatLicenseText(document.license))
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseContent(blocks: List<LicenseBlock>) {
    val dimensions = WalletTheme.dimensions
    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
    )

    Column(verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap)) {
        blocks.forEach { block ->
            when (block) {
                is LicenseBlock.Title -> Column(modifier = Modifier.fillMaxWidth()) {
                    block.lines.forEach { line ->
                        Text(
                            text = linkedLicenseText(line, linkStyle),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                is LicenseBlock.Heading -> Text(
                    text = linkedLicenseText(block.text, linkStyle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = dimensions.relatedGap)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is LicenseBlock.Paragraph -> Text(
                    text = linkedLicenseText(block.text, linkStyle),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                is LicenseBlock.ListItem -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = block.marker,
                        modifier = Modifier.width(LIST_MARKER_WIDTH),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = linkedLicenseText(block.text, linkStyle),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun linkedLicenseText(text: String, linkStyle: TextLinkStyles): AnnotatedString =
    buildAnnotatedString {
        append(text)
        findUrls(text).forEach { range ->
            addLink(
                url = LinkAnnotation.Url(text.substring(range), linkStyle),
                start = range.first,
                end = range.last + 1,
            )
        }
    }

private fun readBundledAsset(context: Context, name: String): String =
    context.assets.open(name).bufferedReader().use { it.readText() }

private sealed interface LicenseDocument {
    data class Ready(val notice: String, val license: String) : LicenseDocument
    data object Unavailable : LicenseDocument
}

private val LIST_MARKER_WIDTH = 40.dp

@Preview(name = "License read failure light", showBackground = true, widthDp = 360)
@Preview(name = "License read failure dark", showBackground = true, widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LicenseReadFailurePreview() {
    MyWalletTheme(dynamicColor = false) {
        AppLicenseScreen(onDone = {}, readAsset = { _, _ -> throw IOException("unavailable") })
    }
}
