package org.aristonis.mywallet.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.BuildConfig
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class OfflineIdentityScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aboutShowsInstalledVersionAndExplainsChosenExportDestination() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                AboutScreen(onDone = {}, onLicense = {}, onPrivacy = {}, onSupport = {})
            }
        }

        compose.onNodeWithText(BuildConfig.VERSION_NAME, substring = true).assertIsDisplayed()
        compose.onNodeWithText("MyWallet by Aristonis", substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.identity_about_export))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aboutOpensTheOfflineApacheLicenseAndReturns() {
        var showingLicense by mutableStateOf(false)
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                if (showingLicense) {
                    AppLicenseScreen(onDone = { showingLicense = false })
                } else {
                    AboutScreen(
                        onDone = {},
                        onLicense = { showingLicense = true },
                        onPrivacy = {},
                        onSupport = {},
                    )
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.identity_app_license_action))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.identity_app_license_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.w2_project_license_heading))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", substring = true)
            .assertExists()
        compose.onNodeWithText(context.getString(R.string.identity_app_license_notice), substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_navigate_back)).performClick()
        compose.onNodeWithText(BuildConfig.VERSION_NAME, substring = true).assertIsDisplayed()
    }

    @Test
    fun packagedProjectLicenseMatchesTheOfficialApacheText() {
        val license = context.assets.open("LICENSE").use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(license)
            .joinToString("") { "%02x".format(it) }

        org.junit.Assert.assertEquals(
            "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
            digest,
        )
        org.junit.Assert.assertTrue(context.assets.open("NOTICE").bufferedReader().use {
            it.readText().contains("Copyright (c) 2025 Aristonis")
        })
    }

    @Test
    fun projectLicenseReadFailureShowsAnErrorAndKeepsBackAvailable() {
        var returned = false
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                AppLicenseScreen(
                    onDone = { returned = true },
                    readAsset = { _, _ -> throw IOException("unreadable asset") },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.w2_license_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.identity_app_license_notice)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_navigate_back)).performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(returned) }
    }

    @Test
    fun emptyPackagedProjectLicenseShowsAnErrorInsteadOfAnEmptyDocument() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                AppLicenseScreen(onDone = {}, readAsset = { _, _ -> "" })
            }
        }

        compose.onNodeWithText(context.getString(R.string.w2_license_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.identity_app_license_notice)).assertDoesNotExist()
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
