package org.aristonis.mywallet.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletListRowLargeTextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longNameAndAmountRemainCompleteAtTwoHundredPercentInRtl() {
        val name = "Long savings account name"
        val amount = "123,456.78 KWD"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).testTag("rowViewport")) {
                        WalletListRow(
                            headline = name,
                            supporting = "Bank · KWD",
                            trailing = { MoneyText(amount) },
                        )
                    }
                }
            }
        }

        listOf(name, amount).forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            if (text == amount) {
                assertTrue(
                    "The complete amount must fit its text bounds",
                    layout.multiParagraph.intrinsics.maxIntrinsicWidth <= layout.size.width,
                )
                assertEquals("The amount must stay on one line", 1, layout.lineCount)
            } else {
                assertFalse("The full name must remain readable", layout.hasVisualOverflow)
            }
        }
        val detail = compose.onNodeWithText("Bank · KWD", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val amountBounds = compose.onNodeWithText(amount, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("rowViewport").fetchSemanticsNode().boundsInRoot
        assertTrue("The amount must sit below the wrapped account copy", amountBounds.top >= detail.bottom)
        assertTrue("The amount must remain inside the row", amountBounds.left >= viewport.left && amountBounds.right <= viewport.right)
    }

    @Test
    fun stackedAmountStillOpensItsRowAtTwoHundredPercentInRtl() {
        var opened = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false, darkTheme = true) {
                    Box(Modifier.width(360.dp)) {
                        WalletListRow(
                            headline = "Long savings account name",
                            trailing = { MoneyText("123,456.78 KWD") },
                            onClick = { opened++ },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("123,456.78 KWD").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun compactTrailingSwitchStillTogglesItsWholeRow() {
        var toggles = 0
        compose.setContent {
            val density = LocalDensity.current
            var checked by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp)) {
                        WalletListRow(
                            headline = "Show archived accounts",
                            modifier = Modifier.toggleable(checked, role = Role.Switch) {
                                checked = it
                                toggles++
                            },
                            trailing = {
                                Switch(
                                    checked = checked,
                                    onCheckedChange = null,
                                    modifier = Modifier.testTag("rowSwitch"),
                                )
                            },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Show archived accounts")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
        compose.onNodeWithTag("rowSwitch", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(1, toggles)
        compose.onNodeWithText("Show archived accounts").performClick()
        assertEquals(2, toggles)
    }

    @Test
    fun compactTrailingExpandActionRemainsIndependent() {
        var expansions = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).testTag("categoryViewport")) {
                        WalletListRow(
                            headline = "Food and household spending",
                            trailing = {
                                Row {
                                    MoneyText("123,456.78 KWD")
                                    IconButton(onClick = { expansions++ }, modifier = Modifier.testTag("expand")) {
                                        Text("Expand")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        val viewport = compose.onNodeWithTag("categoryViewport").fetchSemanticsNode().boundsInRoot
        val amount = compose.onNodeWithText("123,456.78 KWD", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("expand").fetchSemanticsNode().boundsInRoot
        assertTrue("The category amount must stay inside the row", amount.left >= viewport.left && amount.right <= viewport.right)
        assertTrue("Expand must stay inside the row", button.left >= viewport.left && button.right <= viewport.right)
        assertTrue("The amount and Expand must not overlap", amount.right <= button.left || button.right <= amount.left)
        compose.onNodeWithTag("expand").performClick()
        assertEquals(1, expansions)
    }

    @Test
    fun largeTextAlsoStacksAmountOnAWiderPhone() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(400.dp)) {
                        WalletListRow(
                            headline = "Long savings account name",
                            supporting = "Bank · KWD",
                            trailing = { MoneyText("123,456.78 KWD") },
                        )
                    }
                }
            }
        }

        val detail = compose.onNodeWithText("Bank · KWD", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val amount = compose.onNodeWithText("123,456.78 KWD", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Large text must stack even when the phone is wider than 360dp", amount.top >= detail.bottom)
    }
}
