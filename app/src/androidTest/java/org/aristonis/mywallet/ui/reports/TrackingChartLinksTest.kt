package org.aristonis.mywallet.ui.reports

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Tracking opens each chart on its own window, and shows no link that navigation has not wired. */
@RunWith(AndroidJUnit4::class)
class TrackingChartLinksTest {

    @get:Rule val compose = createComposeRule()

    private val window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 9, 3))

    private fun show(
        onOpenSpendingBreakdown: ((TrackingWindow) -> Unit)?,
        onOpenNetWorth: ((TrackingWindow) -> Unit)?,
    ) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                ReportsContent(
                    state = ReportsUiState(window = window, data = ReportsData.Ready("0.00 USD", "0.00 USD", "0.00 USD",
                        emptyList(), emptyList())),
                    windowActions = DateWindowActions.None,
                    onRetry = {},
                    onManageRates = {},
                    exportState = ReportsExportState.Idle,
                    onExport = {},
                    onExportResultShown = {},
                    onOpenSpendingBreakdown = onOpenSpendingBreakdown,
                    onOpenNetWorth = onOpenNetWorth,
                )
            }
        }
    }

    @Test
    fun eachLink_opensItsChartOnTheShownWindow() {
        val opened = mutableListOf<Pair<String, TrackingWindow>>()
        show(onOpenSpendingBreakdown = { opened += "spending" to it }, onOpenNetWorth = { opened += "netWorth" to it })

        compose.onNodeWithTag(TrackingChartLinkTags.SPENDING).performClick()
        compose.onNodeWithTag(TrackingChartLinkTags.NET_WORTH).performClick()

        assertEquals(listOf("spending" to window, "netWorth" to window), opened)
    }

    @Test
    fun withoutCallbacks_noLinkIsShown() {
        show(onOpenSpendingBreakdown = null, onOpenNetWorth = null)

        compose.onNodeWithTag(TrackingChartLinkTags.SPENDING).assertDoesNotExist()
        compose.onNodeWithTag(TrackingChartLinkTags.NET_WORTH).assertDoesNotExist()
    }
}
