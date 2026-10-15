package org.aristonis.mywallet.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Home balance card: the balance, then the three figures as icon rows inside the same card. */
@RunWith(AndroidJUnit4::class)
class HomeSummaryHeroTest {

    @get:Rule val compose = createComposeRule()

    private val long = "28,930,869,589 SYP"
    private val labels = listOf(R.string.home_in_my_pocket, R.string.home_owed_to_me, R.string.home_i_owe)

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun state(pocket: String, owed: String, owe: String, balance: String) = HomeUiState(
        summary = HomeSummaryState.Amounts(pocket, owed, owe, balance),
        isLoading = false,
    )

    private fun show(state: HomeUiState, fontScale: Float = 1f, onDebts: () -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp)) { HomeContent(state, {}, {}, {}, {}, {}, onDebts, {}) }
                }
            }
        }
    }

    @Test
    fun theFiguresSitInsideTheBalanceCardInBalanceOrder() {
        show(state(long, "180 SYP", "550 SYP", long))

        val card = compose.onNodeWithTag(TAG_BALANCE_CARD).getBoundsInRoot()
        val rows = labels.map { compose.onNodeWithContentDescription(text(it), substring = true).getBoundsInRoot() }
        rows.forEach { assertTrue("Each figure is inside the balance card", it.top >= card.top && it.bottom <= card.bottom) }
        assertTrue("Rows follow the balance order", rows[0].bottom <= rows[1].top && rows[1].bottom <= rows[2].top)
    }

    @Test
    fun labelsAreSpokenNotDrawnAndTheEquationLineIsGone() {
        show(state("2,800.00 USD", "180.00 USD", "550.00 USD", "2,430.00 USD"))

        labels.forEach {
            compose.onNodeWithText(text(it)).assertDoesNotExist()
            compose.onNodeWithContentDescription(text(it)).assertIsDisplayed()
        }
        compose.onNode(hasContentDescription(text(R.string.home_owed_to_me)) and hasText("180.00 USD")).assertIsDisplayed()
        compose.onNodeWithText("In my pocket + Owed to me", substring = true).assertDoesNotExist()
    }

    @Test
    fun theBalanceFitsOneLineAtNormalTextOn360Dp() {
        show(state(long, "0 SYP", "0 SYP", long))

        assertEquals("Figure and code share one line", 1, layoutsOf(breakableAtGroups(long))[0].lineCount)
    }

    @Test
    fun atLargeTextTheBalanceCodeWrapsWholeAndNoLetterStandsAlone() {
        show(state(long, "0 SYP", "0 SYP", long), fontScale = 2f)

        val balance = layoutsOf(breakableAtGroups(long))[0]
        val text = balance.layoutInput.text.text
        val codeStart = text.lastIndexOf("SYP")
        assertEquals("The code is on one line", balance.getLineForOffset(codeStart), balance.getLineForOffset(codeStart + 2))
    }

    @Test
    fun noWidthBreaksAFigureInsideItsDigits() {
        var widthDp by mutableIntStateOf(360)
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                Box(Modifier.width(widthDp.dp)) { HomeContent(state(long, "0 SYP", "0 SYP", long), {}, {}, {}, {}, {}, {}, {}) }
            }
        }

        for (width in listOf(320, 340, 360, 400, 448, 520)) {
            compose.runOnIdle { widthDp = width }
            compose.waitForIdle()
            layoutsOf(breakableAtGroups(long)).forEach { assertBreaksOnlyBetweenGroups(it, "at $width dp") }
        }
    }

    @Test
    fun largeTextStillBreaksOnlyBetweenGroups() {
        show(state(long, "0 SYP", "0 SYP", long), fontScale = 2f)

        layoutsOf(breakableAtGroups(long)).forEach { assertBreaksOnlyBetweenGroups(it, "at 200% text") }
    }

    @Test
    fun debtRowsOpenDebtsAndThePocketRowDoesNot() {
        var opens = 0
        show(state("2,800.00 USD", "180.00 USD", "550.00 USD", "2,430.00 USD"), onDebts = { opens++ })

        compose.onNodeWithContentDescription(text(R.string.home_in_my_pocket), substring = true).assertHasNoClickAction()
        compose.onNodeWithContentDescription(text(R.string.home_owed_to_me), substring = true).assertHasClickAction().performClick()
        compose.onNodeWithContentDescription(text(R.string.home_i_owe), substring = true).assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(2, opens) }
    }

    /** Every line ends at a space or after a grouping separator, never between two digits of one group. */
    private fun assertBreaksOnlyBetweenGroups(layout: TextLayoutResult, where: String) {
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            val betweenDigits = text[end - 1].isDigit() && text[end].isDigit()
            assertTrue("$where line $line must not break inside a digit group", !betweenDigits)
        }
    }

    /** Every drawn text that reads [value]: the balance first, then the pocket row. */
    private fun layoutsOf(value: String): List<TextLayoutResult> =
        compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().indices.map { index ->
            val results = mutableListOf<TextLayoutResult>()
            compose.onAllNodesWithText(value, useUnmergedTree = true)[index]
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            results.single()
        }
}
