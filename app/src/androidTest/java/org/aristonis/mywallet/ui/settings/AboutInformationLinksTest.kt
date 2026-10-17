package org.aristonis.mywallet.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutInformationLinksTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aboutOffersPrivacyInformationAsAnOfflineLink() {
        var opens = 0
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                AboutScreen(onDone = {}, onLicense = {}, onPrivacy = { opens++ }, onSupport = {})
            }
        }

        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.identity_privacy_title)
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun aboutOffersSupportAndFaqAsAnOfflineLink() {
        var opens = 0
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                AboutScreen(onDone = {}, onLicense = {}, onPrivacy = {}, onSupport = { opens++ })
            }
        }

        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.identity_support_title)
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun attributionIsSeparateFromTheLicenseAction() {
        var licenseOpens = 0
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                AboutScreen(onDone = {}, onLicense = { licenseOpens++ }, onPrivacy = {}, onSupport = {})
            }
        }

        val licenseLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.identity_app_license_action)
        compose.onNodeWithText("© 2026 Aristonis")
            .performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText(licenseLabel)
            .performScrollTo().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, licenseOpens) }
    }

    @Test
    fun starOpensTheExactRepositoryInTheBrowser() {
        val openedUris = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris.add(uri)
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                MyWalletTheme(dynamicColor = false) {
                    AboutScreen(onDone = {}, onLicense = {}, onPrivacy = {}, onSupport = {})
                }
            }
        }

        compose.onNodeWithText("Star MyWallet on GitHub")
            .performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(listOf("https://github.com/aristonis/MyWallet"), openedUris)
        }
    }

    @Test
    fun siteLinkIsAbsentUntilEnabled() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                AboutScreen(onDone = {}, onLicense = {}, onPrivacy = {}, onSupport = {})
            }
        }

        compose.onNodeWithText("aristonis.org", substring = true, ignoreCase = true)
            .assertDoesNotExist()
    }

}
