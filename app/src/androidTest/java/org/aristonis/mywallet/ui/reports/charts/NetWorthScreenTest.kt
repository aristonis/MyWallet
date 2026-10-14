package org.aristonis.mywallet.ui.reports.charts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import org.aristonis.mywallet.domain.model.NoTrendReason
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Net worth against the signed contract: the restatement label in every state, a line only for a
 * trend, an exact row for every point the line draws, and no converted figure when a rate is missing.
 */
@RunWith(AndroidJUnit4::class)
class NetWorthScreenTest {

    @get:Rule val compose = createComposeRule()

    private val window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 10, 14))

    private fun text(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun point(day: Int, amount: String) =
        NetWorthPointRow(LocalDate.of(2026, 10, day), BigDecimal(amount), "$amount USD")

    private val trend = NetWorthData.Trend(
        currencyCode = "USD",
        points = listOf(point(1, "2100.00"), point(2, "1950.00"), point(3, "2430.00")),
        lowestDisplay = "1950.00 USD",
        highestDisplay = "2430.00 USD",
        direction = TrendDirection.UP,
        crossesZero = false,
    )

    /** The state on screen; a test can swap it, as setContent may run only once per test. */
    private val shown = mutableStateOf(NetWorthUiState(window))

    private fun show(state: NetWorthUiState, fontScale: Float = 1f, onRetry: () -> Unit = {}, onManageRates: () -> Unit = {}) {
        shown.value = state
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).height(640.dp)) {
                        NetWorthContent(shown.value, DateWindowActions.None, onBack = {}, onRetry = onRetry, onManageRates = onManageRates)
                    }
                }
            }
        }
    }

    private fun assertRestatementLabelShown() {
        compose.onNodeWithTag(ChartTags.RESTATEMENT_LABEL).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.net_worth_restatement_label)).assertIsDisplayed()
    }

    @Test
    fun restatementLabel_isVisibleInEveryState() {
        val states = listOf(
            NetWorthUiState(window),
            NetWorthUiState(window, trend),
            NetWorthUiState(window, NetWorthData.CurrentOnly("USD", "2430.00 USD", NoTrendReason.TOO_FEW_POINTS)),
            NetWorthUiState(window, NetWorthData.MissingRate("EUR")),
            NetWorthUiState(window, NetWorthData.TooManyLegs),
            NetWorthUiState(window, loadFailed = true),
        )
        show(states.first())
        states.forEach { state ->
            compose.runOnIdle { shown.value = state }
            assertRestatementLabelShown()
        }
    }

    @Test
    fun aTrend_drawsTheLine_andListsEveryPointItDraws() {
        show(NetWorthUiState(window, trend))

        compose.onNodeWithTag(ChartTags.NET_WORTH_CHART).assertExists()
        trend.points.forEach { point ->
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(point.balanceDisplay))
            compose.onNodeWithText(point.balanceDisplay, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun theLine_isOneImageNode_withVisibleLabels() {
        show(NetWorthUiState(window, trend))

        compose.onNodeWithTag(ChartTags.NET_WORTH_CHART)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
        compose.onNodeWithText(text(R.string.net_worth_label_highest, "\u2068${trend.highestDisplay}\u2069"), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun aWindowChange_showsTheUpdatingIndicator_overTheOldFigures() {
        show(NetWorthUiState(window, trend, isUpdating = true))

        compose.onNodeWithTag(ChartTags.UPDATING).assertIsDisplayed()
        assertRestatementLabelShown()
    }

    @Test
    fun noTrend_showsTheCurrentBalanceAndItsReason_withNoLine() {
        listOf(
            NoTrendReason.START_TOO_OLD to R.string.net_worth_reason_start_too_old,
            NoTrendReason.TOO_FEW_POINTS to R.string.net_worth_reason_too_few_points,
        ).forEachIndexed { index, (reason, reasonText) ->
            val state = NetWorthUiState(window, NetWorthData.CurrentOnly("USD", "2430.00 USD", reason))
            if (index == 0) show(state) else compose.runOnIdle { shown.value = state }

            compose.onNodeWithTag(ChartTags.NET_WORTH_CHART).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.net_worth_no_trend, text(reasonText))).assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.net_worth_current_balance)))
            compose.onNodeWithText(text(R.string.net_worth_current_balance)).assertIsDisplayed()
        }
    }

    @Test
    fun aMissingRate_showsNoLineOrFigure_andOffersRates() {
        var opened = 0
        show(NetWorthUiState(window, NetWorthData.MissingRate("EUR")), onManageRates = { opened++ })

        compose.onNodeWithTag(ChartTags.NET_WORTH_CHART).assertDoesNotExist()
        compose.onNodeWithTag(ChartTags.HERO).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.tracking_manage_rates)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun tooManyLegs_isAnExplicitUnavailableState() {
        show(NetWorthUiState(window, NetWorthData.TooManyLegs))

        compose.onNodeWithText(text(R.string.net_worth_too_many_records)).assertIsDisplayed()
        compose.onNodeWithTag(ChartTags.NET_WORTH_CHART).assertDoesNotExist()
    }

    @Test
    fun aReadFailure_offersRetry_andShowsNoFigure() {
        var retries = 0
        show(NetWorthUiState(window, loadFailed = true), onRetry = { retries++ })

        compose.onNodeWithTag(ChartTags.HERO).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun atLargeText_theLatestBalanceIsShownInFull() {
        val long = trend.copy(points = trend.points + point(4, "123456789.12"), highestDisplay = "123456789.12 USD")
        show(NetWorthUiState(window, long), fontScale = 2f)

        compose.onNodeWithText("123456789.12 USD", useUnmergedTree = true).assertIsDisplayed()
    }
}
