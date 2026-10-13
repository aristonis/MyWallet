package org.aristonis.mywallet.ui.rates

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RatesLargeTextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rateInputAndSaveRemainReachableAtTwoHundredPercentInRtl() {
        var saved: String? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).height(640.dp).testTag("viewport")) {
                        ManageRatesContent(
                            state = ManageRatesUiState(
                                baseCurrencyCode = "USD",
                                isLoading = false,
                                rows = listOf(RateRow("KWD", "د.ك", null, "123456.789")),
                            ),
                            onInputChanged = { _, _ -> },
                            onSave = { saved = it },
                            onDone = {},
                            onRetry = {},
                        )
                    }
                }
            }
        }

        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val inputNode = compose.onNodeWithTag("rate-input-KWD").fetchSemanticsNode()
        val input = inputNode.boundsInRoot
        val inputLayouts = mutableListOf<TextLayoutResult>()
        inputNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(inputLayouts)
        val labelNode = compose.onNodeWithText("Rate in USD", useUnmergedTree = true).fetchSemanticsNode()
        val labelLayouts = mutableListOf<TextLayoutResult>()
        labelNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(labelLayouts)
        val saveNode = compose.onNodeWithText("Save rate", useUnmergedTree = true).fetchSemanticsNode()
        val saveLayouts = mutableListOf<TextLayoutResult>()
        saveNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(saveLayouts)
        val save = compose.onNodeWithText("Save rate").fetchSemanticsNode().boundsInRoot

        assertEquals("123456.789", inputNode.config[SemanticsProperties.EditableText].text)
        assertEquals(listOf("Rate in USD"), inputNode.config[SemanticsProperties.ContentDescription])
        assertFalse("The full rate value must remain readable", inputLayouts.single().hasVisualOverflow)
        assertFalse(
            "The full rate label must remain readable: bounds=${labelNode.boundsInRoot}, " +
                "size=${labelLayouts.single().size}, lines=${labelLayouts.single().lineCount}, " +
                "lineEnd=${labelLayouts.single().getLineEnd(0, visibleEnd = true)}, " +
                "overflowWidth=${labelLayouts.single().didOverflowWidth}, " +
                "overflowHeight=${labelLayouts.single().didOverflowHeight}",
            labelLayouts.single().hasVisualOverflow,
        )
        assertFalse("The full Save label must remain readable", saveLayouts.single().hasVisualOverflow)
        listOf(input, labelNode.boundsInRoot, save).forEach { bounds ->
            assertTrue(
                "Controls must stay inside the viewport",
                bounds.left >= viewport.left && bounds.right <= viewport.right &&
                    bounds.top >= viewport.top && bounds.bottom <= viewport.bottom,
            )
        }
        assertTrue("The rate label must be above its input", labelNode.boundsInRoot.bottom <= input.top)
        assertTrue("Save must be below the input without overlap", save.top >= input.bottom)
        compose.onNodeWithText("Save rate").performClick()
        assertEquals("KWD", saved)
    }
}
