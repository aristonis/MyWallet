package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionTypeLargeTextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun standardTypeLabelsRemainCompleteAtTwoHundredPercentInRtl() {
        var selected by mutableStateOf(TransactionType.EXPENSE)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).testTag("selector")) {
                        TransactionTypeSelector(selected = selected, onSelect = { selected = it })
                    }
                }
            }
        }

        listOf("Income", "Expense", "Transfer").forEach { label ->
            val node = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertEquals("$label needs one text layout", 1, layouts.size)
            val layout = layouts.single()
            assertEquals("$label must remain on one line", 1, layout.lineCount)
            assertEquals("$label must include every character", label.length, layout.getLineEnd(0))
            assertTrue(
                "$label glyphs must fit: left=${layout.getLineLeft(0)}, " +
                    "right=${layout.getLineRight(0)}, width=${layout.size.width}",
                layout.getLineLeft(0) >= -0.5f && layout.getLineRight(0) <= layout.size.width + 0.5f,
            )
            val selector = compose.onNodeWithTag("selector").fetchSemanticsNode().boundsInRoot
            assertTrue("$label must stay within the selector", node.boundsInRoot.left >= selector.left &&
                node.boundsInRoot.right <= selector.right)
            val targetHeight = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height
            assertTrue("$label needs a 48dp tap target", targetHeight >= 48f * compose.density.density)
            compose.onNodeWithText(label).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            )
        }
        compose.onNodeWithText("Expense").assertIsSelected()
        compose.onNodeWithTag("selected-type-EXPENSE", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Transfer").performClick().assertIsSelected()
        compose.onNodeWithTag("selected-type-EXPENSE", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("selected-type-TRANSFER", useUnmergedTree = true).assertExists()
    }
}
