package org.aristonis.mywallet.ui.window

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Tracking and Transactions share these pickers, so what each one hands back has to be exact: the
 * first of the chosen month, the first of the chosen year, and no range until both ends are set.
 */
@RunWith(AndroidJUnit4::class)
class WindowPickersTest {

    @get:Rule val compose = createComposeRule()

    private var picked: LocalDate? = null

    private fun showBar(window: TrackingWindow) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DateWindowBar(window = window, actions = DateWindowActions.None.copy(onJumpTo = { picked = it }))
            }
        }
    }

    @Test
    fun theMonthGridOpensOnTheFirstOfThePickedMonth() {
        showBar(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 10)))

        compose.onNodeWithText("August 2026").performClick()
        compose.onNodeWithText("Mar").performClick()
        compose.onNodeWithText("OK").performClick()

        assertEquals(LocalDate.of(2026, 3, 1), picked)
    }

    @Test
    fun theYearListOpensOnTheFirstOfJanuary() {
        showBar(TrackingWindow.Period(TrackingPeriod.YEAR, LocalDate.of(2026, 8, 10)))

        compose.onNodeWithText("2026").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("2024"))
        compose.onNodeWithText("2024").performClick()

        assertEquals(LocalDate.of(2024, 1, 1), picked)
    }

    @Test
    fun theRangeDialogOpensFromAllTimeWithNothingToConfirm() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DateRangeAction(
                    window = TrackingWindow.Period(TrackingPeriod.ALL_TIME, LocalDate.of(2026, 8, 10)),
                    onSelectRange = { _, _ -> error("nothing was picked") },
                )
            }
        }

        compose.onNodeWithContentDescription("Choose dates").performClick()

        compose.onNodeWithText("OK").assertIsNotEnabled()
    }
}
