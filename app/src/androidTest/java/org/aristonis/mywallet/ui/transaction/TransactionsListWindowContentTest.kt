package org.aristonis.mywallet.ui.transaction

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The history carries the same date bar as Tracking, and an empty list says why it is empty: a
 * filter that matches nothing must not read like an app with no data in it.
 */
@RunWith(AndroidJUnit4::class)
class TransactionsListWindowContentTest {

    @get:Rule val compose = createComposeRule()

    private fun show(window: TrackingWindow) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                TransactionsListContent(
                    state = TransactionsUiState(sections = emptyList(), isLoading = false, window = window),
                    windowActions = DateWindowActions.None,
                    onAddTransaction = {},
                    onEditTransaction = {},
                )
            }
        }
    }

    @Test
    fun anEmptyMonthSaysNothingMatches() {
        show(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 10)))

        compose.onNodeWithText("August 2026").assertIsDisplayed()
        compose.onNodeWithText("Nothing in this range.").assertIsDisplayed()
        compose.onNodeWithText("No transactions yet. Add your first transaction.").assertDoesNotExist()
    }

    @Test
    fun anEmptyHistoryInvitesTheFirstTransaction() {
        show(TrackingWindow.Period(TrackingPeriod.ALL_TIME, LocalDate.of(2026, 8, 10)))

        compose.onNodeWithText("No transactions yet. Add your first transaction.").assertIsDisplayed()
        compose.onNodeWithText("Nothing in this range.").assertDoesNotExist()
    }

    @Test
    fun theTopBarOffersACustomRange() {
        show(TrackingWindow.Period(TrackingPeriod.ALL_TIME, LocalDate.of(2026, 8, 10)))

        compose.onNodeWithContentDescription("Choose dates").assertIsDisplayed()
    }
}
