package org.aristonis.mywallet.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The whole row is one switch: one target to tap, announced as a switch with its state. */
@RunWith(AndroidJUnit4::class)
class SettingsSwitchRowTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun theRowIsOneSwitchThatReportsItsState() {
        var checked = true
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                SettingsSwitchRow(
                    label = "Save rates I enter on transactions",
                    checked = checked,
                    onCheckedChange = { changes += it },
                )
            }
        }

        compose.onNodeWithText("Save rates I enter on transactions", useUnmergedTree = false)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOn()
            .performClick()

        assertEquals(listOf(false), changes)
    }

    @Test
    fun anOffSwitchReadsAsOff() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                SettingsSwitchRow(label = "Save rates", checked = false, onCheckedChange = {})
            }
        }

        compose.onNodeWithText("Save rates").assertIsOff()
    }
}
