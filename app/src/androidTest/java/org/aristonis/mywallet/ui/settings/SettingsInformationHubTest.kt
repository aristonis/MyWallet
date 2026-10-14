package org.aristonis.mywallet.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsInformationHubTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun informationGroupHasOneAboutEntryAndNoDirectPrivacyOrSupportEntries() {
        var aboutOpens = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                SettingsContent(
                    state = BackupUiState(),
                    currentTheme = ThemePreference.SYSTEM,
                    baseCurrencyCode = "USD",
                    saveTransactionRates = false,
                    onSaveTransactionRatesChanged = {},
                    onThemeSelected = {},
                    onExport = {},
                    onImport = {},
                    onStatusShown = {},
                    onManageAccounts = {},
                    onManageCategories = {},
                    onManageDeductions = {},
                    onManageRates = {},
                    onChangeBaseCurrency = {},
                    onAbout = { aboutOpens++ },
                )
            }
        }

        val about = context.getString(R.string.w2_about_title)
        compose.onAllNodesWithText(about).assertCountEquals(1)
        compose.onAllNodesWithText(context.getString(R.string.identity_privacy_title)).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.identity_support_title)).assertCountEquals(0)
        compose.onNodeWithText(about).performScrollTo().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, aboutOpens) }
    }
}
