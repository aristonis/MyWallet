package org.aristonis.mywallet.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Tracking against the signed frame: three labelled summary cells, collapsed rows that name their
 * parts, one note for a window with nothing in it, and a way to the Exchange rates screen when a
 * rate is missing. Shapes are checked at 360dp, normal and 200% text.
 */
@RunWith(AndroidJUnit4::class)
class ReportsParityTest {

    @get:Rule val compose = createComposeRule()

    private val food = CategoryRow(
        id = 7,
        label = CategoryLabel.Named("Food"),
        totalDisplay = "620.00 USD",
        subCategories = listOf(
            SubCategoryRow(71, CategoryLabel.Named("Groceries"), "500.00 USD"),
            SubCategoryRow(72, CategoryLabel.Named("Dining"), "100.00 USD"),
            SubCategoryRow(null, CategoryLabel.NoSubCategory, "20.00 USD"),
        ),
    )

    private fun ready(income: List<CategoryRow> = emptyList(), expense: List<CategoryRow> = listOf(food)) =
        ReportsData.Ready("2,000.00 USD", "1,275.50 USD", "724.50 USD", income, expense)

    private fun show(
        data: ReportsData,
        fontScale: Float = 1f,
        onManageRates: () -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).height(640.dp).testTag("viewport")) {
                        ReportsContent(
                            state = ReportsUiState(
                                window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 7, 15)),
                                data = data,
                            ),
                            windowActions = DateWindowActions.None,
                            onRetry = {},
                            onManageRates = onManageRates,
                            exportState = ReportsExportState.Idle,
                            onExport = {},
                            onExportResultShown = {},
                        )
                    }
                }
            }
        }
    }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun incomeAndExpensesShareARowAndNetSitsBelowThemAtNormalTextSize() {
        show(ready())

        assertEquals(top("Income"), top("Expenses"), 0.5f)
        assertTrue("Net should be below the other two", top("Net") > top("Income"))
        listOf("2,000.00 USD", "1,275.50 USD", "724.50 USD").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun longFiguresStackAtNormalTextSizeAndStayWholeInsideTheScreen() {
        val long = ReportsData.Ready("1,234,567,890.12 KWD", "987,654,321.09 KWD", "246,913,569.03 KWD", emptyList(), listOf(food))
        show(long)

        assertTrue("Expenses should sit below Income", top("Expenses") > top("Income"))
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        listOf("1,234,567,890.12 KWD", "987,654,321.09 KWD", "246,913,569.03 KWD").forEach {
            val bounds = compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot
            assertTrue("$it must stay inside the screen", bounds.left >= viewport.left && bounds.right <= viewport.right)
        }
    }

    @Test
    fun theThreeCellsStackAtTwoHundredPercentText() {
        show(ready(), fontScale = 2f)

        assertTrue(top("Expenses") > top("Income"))
        assertTrue(top("Net") > top("Expenses"))
        listOf("2,000.00 USD", "1,275.50 USD", "724.50 USD").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun aCollapsedCategoryNamesItsFirstTwoPartsAndTheNamesGoWhenItOpens() {
        show(ready())
        compose.onNodeWithText("Groceries · Dining").assertIsDisplayed()

        compose.onNode(hasContentDescription("Sub-categories of Food")).performClick()

        compose.onNodeWithText("Groceries · Dining").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(androidx.compose.ui.test.hasText("Groceries"))
        compose.onNodeWithText("Groceries").assertIsDisplayed()
    }

    @Test
    fun aWindowWithNothingInItShowsOneNoteInsteadOfTheSummaryAndTheEmptySections() {
        show(ready(income = emptyList(), expense = emptyList()))

        compose.onNodeWithText("Nothing to track in this period").assertIsDisplayed()
        compose.onNodeWithText("Choose another period or record a transaction.").assertIsDisplayed()
        compose.onNodeWithText("Income").assertDoesNotExist()
        compose.onNodeWithText("Spending by category").assertDoesNotExist()
        compose.onNodeWithText("No spending this period.").assertDoesNotExist()
    }

    @Test
    fun aMissingRateOffersTheExchangeRatesScreen() {
        var opened = 0
        show(ReportsData.MissingRate("EUR"), onManageRates = { opened++ })

        compose.onNodeWithText("Manage rates").assertIsDisplayed().performClick()

        assertEquals(1, opened)
    }
}
