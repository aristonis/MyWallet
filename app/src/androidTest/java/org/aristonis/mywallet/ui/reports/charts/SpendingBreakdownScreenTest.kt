package org.aristonis.mywallet.ui.reports.charts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Spending breakdown against the signed contract: the exact table beside the chart, a refund effect
 * named in words with its sign, no chart for a window with no spending, and Rates when a rate is
 * missing.
 */
@RunWith(AndroidJUnit4::class)
class SpendingBreakdownScreenTest {

    @get:Rule val compose = createComposeRule()

    private val window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 10, 14))

    private fun text(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun row(id: Long, name: String, amount: String) =
        SpendingRow(id, CategoryLabel.Named(name), BigDecimal(amount), "$amount USD", emptyList())

    private fun show(state: SpendingUiState, fontScale: Float = 1f, onRetry: () -> Unit = {}, onManageRates: () -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).height(640.dp)) {
                        SpendingBreakdownContent(state, DateWindowActions.None, onBack = {}, onRetry = onRetry, onManageRates = onManageRates)
                    }
                }
            }
        }
    }

    private fun scrollTo(value: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(value))
    }

    @Test
    fun theTableListsEveryCategoryTheChartDraws() {
        val data = SpendingData.Ready("USD", "197.40 USD", listOf(row(1, "Food", "126.40"), row(2, "Transport", "71.00")))
        show(SpendingUiState(window, data))

        compose.onNodeWithTag(ChartTags.SPENDING_CHART)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
        data.rows.forEach { row ->
            scrollTo(row.amountDisplay)
            compose.onNodeWithText(row.amountDisplay, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun aRefundEffect_isNamedWithItsSign() {
        val data = SpendingData.Ready("USD", "32.00 USD", listOf(row(2, "Transport", "40.00"), row(1, "Food", "-8.00")))
        show(SpendingUiState(window, data))

        compose.onNodeWithText(text(R.string.spending_refund_note)).assertIsDisplayed()
        scrollTo("-8.00 USD")
        compose.onNodeWithText(text(R.string.spending_refund_row), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("-8.00 USD", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun onlyRefunds_drawNoChart() {
        val data = SpendingData.Ready("USD", "-8.00 USD", listOf(row(1, "Food", "-8.00")))
        show(SpendingUiState(window, data))

        compose.onNodeWithTag(ChartTags.SPENDING_CHART).assertDoesNotExist()
    }

    @Test
    fun noSpending_showsTheZeroTotal_andNoChart() {
        show(SpendingUiState(window, SpendingData.Ready("USD", "0.00 USD", emptyList())))

        compose.onNodeWithText("0.00 USD", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.spending_empty)).assertIsDisplayed()
        compose.onNodeWithTag(ChartTags.SPENDING_CHART).assertDoesNotExist()
    }

    @Test
    fun aMissingRate_showsNoFigure_andOffersRates() {
        var opened = 0
        show(SpendingUiState(window, SpendingData.MissingRate("EUR")), onManageRates = { opened++ })

        compose.onNodeWithTag(ChartTags.HERO).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.spending_missing_rate, "EUR")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tracking_manage_rates)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aReadFailure_offersRetry() {
        var retries = 0
        show(SpendingUiState(window, loadFailed = true), onRetry = { retries++ })

        compose.onNodeWithTag(ChartTags.HERO).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun atLargeText_aLongAmountIsShownInFull() {
        val data = SpendingData.Ready(
            "KWD", "123,456,789.125 KWD",
            listOf(SpendingRow(1, CategoryLabel.Named("Household maintenance and repairs"), BigDecimal("123456789.125"),
                "123,456,789.125 KWD", emptyList())),
        )
        show(SpendingUiState(window, data), fontScale = 2f)

        scrollTo("123,456,789.125 KWD")
        compose.onNodeWithText("123,456,789.125 KWD", useUnmergedTree = true).assertExists()
    }
}
