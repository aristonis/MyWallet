package org.aristonis.mywallet.ui.transaction

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoreKindsSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun standardSelectorKeepsThreeChoicesAndMoreOpensTheFiveDebtKinds() {
        val form = TransactionFormHarness(TransactionType.EXPENSE)
        compose.setContent { MyWalletTheme(dynamicColor = false) { form.Content() } }
        listOf("Income", "Expense", "Transfer").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        labels.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("More transaction kinds").assertIsDisplayed()
        labels.forEach { label ->
            val node = compose.onNode(hasText(label) and hasClickAction()).performScrollTo()
            node.assertIsDisplayed()
            val height = node.fetchSemanticsNode().boundsInRoot.height
            assertTrue("$label must have a 48dp tap target", height >= 48f * compose.density.density)
        }
        compose.onNodeWithText("Refund").assertDoesNotExist()
        compose.formScreenshot("more-kinds-five-choices")
    }

    @Test fun everyMoreChoiceSelectsItsKindAndDismissesTheSheet() {
        var visible by mutableStateOf(true)
        var chosen: TransactionType? = null
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                if (visible) MoreKindsSheet(onTypeSelected = { chosen = it }, onDismiss = { visible = false })
            }
        }
        moreTransactionTypes.zip(labels).forEach { (type, label) ->
            compose.runOnIdle { visible = true }
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(type, chosen); assertEquals(false, visible) }
            compose.onNodeWithText("More transaction kinds").assertDoesNotExist()
        }
    }

    @Test fun lastChoiceRemainsReachableInDarkRtlAtTwoHundredPercentFont() {
        var chosen: TransactionType? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f)) {
                MyWalletTheme(darkTheme = true, dynamicColor = false) {
                    MoreKindsSheet(onTypeSelected = { chosen = it }, onDismiss = {})
                }
            }
        }
        compose.onNodeWithText("Earned, not paid yet").performScrollTo().assertIsDisplayed()
        compose.formScreenshot("more-kinds-dark-rtl-font200-last-choice")
        compose.onNodeWithText("Earned, not paid yet").performClick()
        compose.runOnIdle { assertEquals(TransactionType.EARNED_UNPAID, chosen) }
    }

    private val labels = listOf("Borrowed", "Lent", "Owed from before", "Bought on credit", "Earned, not paid yet")
}
