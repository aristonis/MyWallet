package org.aristonis.mywallet.ui.transaction

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.junit.Assert.assertTrue
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
                    onShowSavedEntry = {},
                    onSavedEntryMessageDone = {},
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

    @Test
    fun anEntrySavedOutsideTheDatesOffersToShowIt() {
        var shown = false
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                TransactionsListContent(
                    state = TransactionsUiState(
                        sections = emptyList(),
                        isLoading = false,
                        window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 9, 15)),
                        savedOutsideWindow = LocalDate.of(2026, 8, 30),
                    ),
                    windowActions = DateWindowActions.None,
                    onAddTransaction = {},
                    onEditTransaction = {},
                    onShowSavedEntry = { shown = true },
                    onSavedEntryMessageDone = {},
                )
            }
        }

        compose.onNodeWithText("outside these dates", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Show").performClick()
        compose.waitForIdle()
        assertTrue(shown)
    }

    @Test
    fun aMessageNotAnsweredOnScreenIsLetGo() {
        // Leaving for the editor or another tab must not bring back a snackbar about an old save later.
        var letGo = false
        var onScreen by mutableStateOf(true)
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                if (onScreen) {
                    TransactionsListContent(
                        state = TransactionsUiState(
                            isLoading = false,
                            window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 9, 15)),
                            savedOutsideWindow = LocalDate.of(2026, 8, 30),
                        ),
                        windowActions = DateWindowActions.None,
                        onAddTransaction = {},
                        onEditTransaction = {},
                        onShowSavedEntry = {},
                        onSavedEntryMessageDone = { letGo = true },
                    )
                }
            }
        }
        compose.onNodeWithText("outside these dates", substring = true).assertIsDisplayed()

        onScreen = false
        compose.waitForIdle()

        assertTrue(letGo)
    }

    @Test
    fun aFailedReadSaysSo() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                TransactionsListContent(
                    state = TransactionsUiState(
                        isLoading = false,
                        window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 10)),
                        loadFailed = true,
                    ),
                    windowActions = DateWindowActions.None,
                    onAddTransaction = {},
                    onEditTransaction = {},
                    onShowSavedEntry = {},
                    onSavedEntryMessageDone = {},
                )
            }
        }

        compose.onNodeWithText("Some of your data could not be read", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Nothing in this range.").assertDoesNotExist()
    }
}
