package org.aristonis.mywallet.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsWorkingRowsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun workingBackupDisablesOnlyDataActionsUntilItFinishes() {
        var exports = 0
        var restores = 0
        var accountOpens = 0
        var backupState by mutableStateOf(BackupUiState(BackupStatus.Working))
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                SettingsContent(
                    state = backupState,
                    currentTheme = ThemePreference.SYSTEM,
                    baseCurrencyCode = "USD",
                    saveTransactionRates = false,
                    onSaveTransactionRatesChanged = {},
                    onThemeSelected = {},
                    onExport = { exports++ },
                    onImport = { restores++ },
                    onStatusShown = {},
                    onManageAccounts = { accountOpens++ },
                    onManageCategories = {},
                    onManageDeductions = {},
                    onManageRates = {},
                    onChangeBaseCurrency = {},
                    onAbout = {},
                    onPrivacy = {},
                    onSupport = {},
                )
            }
        }

        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val export = context.getString(R.string.settings_export)
        val restore = context.getString(R.string.settings_restore)
        compose.onNodeWithText(export).performScrollTo().assertIsNotEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText(restore).performScrollTo().assertIsNotEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText(context.getString(R.string.settings_accounts))
            .performScrollTo().assertIsEnabled().performClick()

        assertEquals(0, exports)
        assertEquals(0, restores)
        assertEquals(1, accountOpens)

        compose.runOnIdle { backupState = BackupUiState(BackupStatus.Idle) }
        compose.onNodeWithText(export).performScrollTo().assertIsEnabled()
            .assertHasClickAction().performClick()
        compose.onNodeWithText(restore).performScrollTo().assertIsEnabled()
            .assertHasClickAction().performClick()

        assertEquals(1, exports)
        assertEquals(1, restores)
    }
}
