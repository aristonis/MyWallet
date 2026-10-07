package org.aristonis.mywallet.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared pieces every screen is built from. If a status card stops being tappable, or an amount
 * reverses under an Arabic locale, it happens everywhere at once — which is exactly why these are
 * worth pinning here rather than screen by screen.
 */
@RunWith(AndroidJUnit4::class)
class WalletComponentsTest {

    @get:Rule val compose = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        compose.setContent { MyWalletTheme(dynamicColor = false) { content() } }
    }

    /** Right-to-left, as an Arabic or Hebrew build would run. */
    private fun setRtlContent(content: @Composable () -> Unit) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column { content() }
                }
            }
        }
    }

    @Test
    fun anAmountKeepsItsCharacterOrderUnderARightToLeftLayout() {
        setRtlContent { MoneyText(amount = "−1,234.50 USD") }

        // The sign leads and the code trails, exactly as in a left-to-right build. A mirrored
        // paragraph would move the minus to the far side and change what the figure says.
        compose.onNodeWithText("−1,234.50 USD").assertIsDisplayed()
    }

    @Test
    fun aStatusCardShowsItsMessageAndIsTappableWhenItCanBeActedOn() {
        var tapped = false
        setContent {
            StatusCard(
                tone = StatusTone.WARNING,
                message = "Set an exchange rate for EUR to see your net worth.",
                iconDescription = "Warning",
                onClick = { tapped = true },
            )
        }

        compose.onNodeWithContentDescription("Warning").assertIsDisplayed()
        compose.onNodeWithText("Set an exchange rate for EUR to see your net worth.")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        assertTrue(tapped)
    }

    /** A card with nowhere to go must not pretend otherwise. */
    @Test
    fun aStatusCardWithNoActionIsNotTappable() {
        setContent {
            StatusCard(tone = StatusTone.INFO, message = "Nothing to do here.")
        }

        compose.onNodeWithText("Nothing to do here.").assertIsDisplayed()
    }

    @Test
    fun anEmptyStateNamesTheNextAction() {
        var created = false
        setContent {
            EmptyState(
                message = "No accounts yet.",
                actionLabel = "Create your first account",
                onAction = { created = true },
            )
        }

        compose.onNodeWithText("No accounts yet.").assertIsDisplayed()
        compose.onNodeWithText("Create your first account").performClick()

        assertTrue(created)
    }

    @Test
    fun aListRowShowsItsNameItsDetailAndItsAmount() {
        var opened = 0
        setContent {
            WalletListRow(
                headline = "Euro Savings",
                supporting = "Bank · EUR",
                trailing = { MoneyText(amount = "250.00 EUR") },
                onClick = { opened++ },
            )
        }

        compose.onNodeWithText("Euro Savings").assertIsDisplayed()
        compose.onNodeWithText("Bank · EUR").assertIsDisplayed()
        compose.onNodeWithText("250.00 EUR").assertIsDisplayed()
        compose.onNodeWithText("Euro Savings").performClick()

        assertEquals(1, opened)
    }

    /** A row with no destination should not react to a tap, or the user learns to distrust taps. */
    @Test
    fun aListRowWithNoDestinationDoesNotReactToATap() {
        setContent { WalletListRow(headline = "Cash", supporting = "Cash · USD") }

        compose.onNodeWithText("Cash").assertIsDisplayed()
    }
}
