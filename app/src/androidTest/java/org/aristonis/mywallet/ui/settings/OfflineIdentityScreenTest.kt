package org.aristonis.mywallet.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.BuildConfig
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineIdentityScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aboutShowsInstalledVersionAndExplainsChosenExportDestination() {
        compose.setContent { MyWalletTheme(dynamicColor = false) { AboutScreen(onDone = {}) } }

        compose.onNodeWithText(BuildConfig.VERSION_NAME, substring = true).assertIsDisplayed()
        compose.onNodeWithText("MyWallet by Aristonis", substring = true).assertIsDisplayed()
        compose.onNodeWithText("export", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun privacyInformationIsReadableOffline() {
        compose.setContent { MyWalletTheme(dynamicColor = false) { PrivacyInformationScreen(onDone = {}) } }

        compose.onNodeWithText("Privacy information").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.identity_privacy_storage)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.identity_privacy_export)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun supportGuidanceCoversSafeReportsAndRestore() {
        compose.setContent { MyWalletTheme(dynamicColor = false) { SupportFaqScreen(onDone = {}) } }

        compose.onNodeWithText("Support and FAQ").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.identity_support_backup)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.identity_support_report)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("https://github.com/aristonis/MyWallet/issues", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("aristonis@outlook.com", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

}
