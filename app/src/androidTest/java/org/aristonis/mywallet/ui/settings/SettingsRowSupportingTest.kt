package org.aristonis.mywallet.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRowSupportingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun walletAndCurrencyRowsSayWhatIsBehindThem() {
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
                    onAbout = {},
                )
            }
        }

        listOf(
            R.string.settings_base_currency_body,
            R.string.settings_accounts_body,
            R.string.settings_categories_body,
            R.string.settings_deductions_body,
            R.string.settings_rates_body,
        ).forEach { line ->
            compose.onNodeWithText(context.getString(line)).performScrollTo().assertIsDisplayed()
        }
    }
}
