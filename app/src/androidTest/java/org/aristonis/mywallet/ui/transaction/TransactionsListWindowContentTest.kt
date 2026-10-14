package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.CompactDateWindowBar
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

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

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
                    onRetry = {},
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
    fun compactDateBarShowsTheDateRangeActionLabel() {
        show(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 10)))

        compose.onNodeWithText("Date range").assertIsDisplayed()
    }

    @Test
    fun largeTextRtlDateRangeActionLeavesTheSelectedMonthItsOwnRow() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp)) {
                        CompactDateWindowBar(
                            window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 10)),
                            actions = DateWindowActions.None,
                        )
                    }
                }
            }
        }

        val month = compose.onNodeWithText("August 2026").assertIsDisplayed().getBoundsInRoot()
        val range = compose.onNodeWithText("Date range").assertIsDisplayed().getBoundsInRoot()
        assertTrue("At 200% text the date action must clear the selected month", range.top >= month.bottom)
        compose.onNodeWithContentDescription("Previous period").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next period").assertIsDisplayed()
    }

    @Test
    fun compactDateControlAnnouncesTheSelectedMonth() {
        show(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 10)))
        compose.onNodeWithContentDescription("Period, Month, August 2026").assertIsDisplayed()
    }

    @Test
    fun compactDateControlAnnouncesTheCustomDateRange() {
        show(TrackingWindow.Custom(DateRange(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 17))))
        compose.onNode(
            hasContentDescription("Custom dates", substring = true)
                .and(hasContentDescription("2026", substring = true)),
        ).assertIsDisplayed()
    }

    @Test
    fun monthUsesOneCompactDateControlWithEveryPeriodStillReachable() {
        var picked: TrackingPeriod? = null
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                TransactionsListContent(
                    state = TransactionsUiState(
                        sections = emptyList(),
                        isLoading = false,
                        window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 10)),
                    ),
                    windowActions = DateWindowActions.None.copy(onSelectPeriod = { picked = it }),
                    onAddTransaction = {},
                    onEditTransaction = {},
                    onShowSavedEntry = {},
                    onSavedEntryMessageDone = {},
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithText("August 2026").assertIsDisplayed()
        compose.onNodeWithText("Day").assertDoesNotExist()
        compose.onNodeWithContentDescription("Period, Month, August 2026").performClick()
        compose.onNode(
            hasText("Year").and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)),
        ).performClick()
        assertTrue(picked == TrackingPeriod.YEAR)
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
                    onRetry = {},
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
                        onRetry = {},
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
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithText(text(R.string.transactions_data_unreadable)).assertIsDisplayed()
        compose.onNodeWithText("Nothing in this range.").assertDoesNotExist()
    }
}
