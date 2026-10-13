package org.aristonis.mywallet.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ReportsLargeTextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun summaryKeepsLongAmountsCompleteAtTwoHundredPercentInRtl() {
        val amounts = listOf("1,234,567,890.12 KWD", "987,654,321.09 KWD", "246,913,569.03 KWD")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).height(640.dp).testTag("viewport")) {
                        ReportsContent(
                            state = ReportsUiState(
                                window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 10)),
                                data = ReportsData.Ready(
                                    incomeDisplay = amounts[0],
                                    expenseDisplay = amounts[1],
                                    netDisplay = amounts[2],
                                    incomeCategories = emptyList(),
                                    expenseCategories = emptyList(),
                                ),
                            ),
                            windowActions = DateWindowActions.None,
                            onRetry = {},
                            exportState = ReportsExportState.Idle,
                            onExport = {},
                            onExportResultShown = {},
                        )
                    }
                }
            }
        }

        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        amounts.forEach { amount ->
            val node = compose.onNodeWithText(amount, useUnmergedTree = true)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val layouts = mutableListOf<TextLayoutResult>()
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            assertFalse(
                "$amount must remain fully readable (lines=${layout.lineCount}, " +
                    "layout=${layout.size}, bounds=$bounds, viewport=$viewport)",
                layout.hasVisualOverflow,
            )
            assertTrue("$amount must stay inside the viewport", bounds.left >= viewport.left && bounds.right <= viewport.right)
        }
    }
}
