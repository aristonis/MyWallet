package org.aristonis.mywallet.ui.settings

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
}
