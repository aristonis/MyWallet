package org.aristonis.mywallet.ui.debt

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtDetail
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DebtState
import org.aristonis.mywallet.domain.model.DebtSummary
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DebtDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 10, 12)
    private val debt = Debt(1, "Ali", DebtSide.I_OWE, "USD")
    private fun entry(id: Long) = Transaction.OwedBefore(id, debt.id, debt.side, Money.of("10", "USD"), day, "History receipt $id")
    private fun state(entries: List<Transaction> = listOf(entry(1))) = DebtDetailUiState(
        detail = DebtDetail(DebtSummary(debt, Money.of("10", "USD"), DebtState.OPEN), entries),
        counterpartyInput = "Ali", remainingDisplay = "10.00 USD", isLoading = false,
    )

    @Test fun renameCollisionStaysInlineAndKeepsTheTypedName() {
        var current by mutableStateOf(state())
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DebtDetailContent(current, onDone = {}, onEditEntry = {},
                    onCounterpartyChange = { current = current.copy(counterpartyInput = it) },
                    onRename = { current = current.copy(error = UiMessage.DebtNameTaken(current.counterpartyInput)) },
                    onDueDateChange = {}, onNoteChange = {}, onSaveDetails = {}, onRetry = {}, today = { LocalDate.of(2026, 10, 10) },
                    onWriteOff = {}, onDeleteDebt = {}, snackbar = SnackbarHostState())
            }
        }
        compose.onNodeWithText("Counterparty").performScrollTo().performTextReplacement("Omar")
        compose.onNodeWithText("Rename").performScrollTo().performClick()
        compose.onNodeWithText("Counterparty").assertTextContains("Omar")
        compose.onNode(hasText("A debt named", substring = true) and hasText("already exists on this side and currency.", substring = true)).assertIsDisplayed()
    }

    @Test fun noteCanBeSavedSeparatelyAndHistoryOpensItsOriginalEntryId() {
        var current by mutableStateOf(state())
        var saved = ""
        var opened: Long? = null
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DebtDetailContent(current, onDone = {}, onEditEntry = { opened = it }, onCounterpartyChange = {},
                    onRename = {}, onDueDateChange = {}, onNoteChange = { current = current.copy(noteInput = it) },
                    onSaveDetails = { saved = current.noteInput }, onRetry = {}, today = { LocalDate.of(2026, 10, 10) },
                    onWriteOff = {}, onDeleteDebt = {}, snackbar = SnackbarHostState())
            }
        }
        compose.onNodeWithText("Note").performScrollTo().performTextReplacement("rent reminder")
        compose.onNodeWithText("Save details").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("rent reminder", saved) }
        compose.reachDebtRow("History receipt 1")
        compose.onNodeWithText("History receipt 1").performClick()
        compose.runOnIdle { assertEquals(1L, opened) }
    }

    @Test fun finalHistoryEntryIsReachableInDarkRtlAtTwoHundredPercentFont() {
        var opened: Long? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f)) {
                MyWalletTheme(darkTheme = true, dynamicColor = false) {
                    DebtDetailContent(state((1L..30L).map(::entry)), onDone = {}, onEditEntry = { opened = it },
                        onCounterpartyChange = {}, onRename = {}, onDueDateChange = {}, onNoteChange = {}, onSaveDetails = {}, onRetry = {}, today = { LocalDate.of(2026, 10, 10) },
                    onWriteOff = {}, onDeleteDebt = {}, snackbar = SnackbarHostState())
                }
            }
        }
        compose.reachDebtRow("History receipt 30")
        compose.onNodeWithText("History receipt 30").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(30L, opened) }
    }
}
