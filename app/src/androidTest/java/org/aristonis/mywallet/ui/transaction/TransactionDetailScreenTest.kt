package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TransactionDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    private val day = LocalDate.of(2026, 6, 1)

    private val expenseRow = TransactionRow(
        id = 5, date = day, type = TransactionRowType.EXPENSE,
        accountName = "Cash wallet", destAccountName = null,
        categoryLabel = CategoryLabel.Named("Groceries"),
        amount = Money.of("50", "USD"), destAmount = null,
        amountDisplay = "50.00 USD", destAmountDisplay = null, note = "weekly shop",
    )

    private val incomeRow = TransactionRow(
        id = 6, date = day, type = TransactionRowType.INCOME,
        accountName = "Main bank", destAccountName = null,
        categoryLabel = CategoryLabel.Named("Salary"),
        amount = Money.of("1000", "USD"), destAmount = null,
        amountDisplay = "1,000.00 USD", destAmountDisplay = null, note = null,
        grossAmount = Money.of("1000", "USD"), grossAmountDisplay = "1,000.00 USD",
        accountMovement = Money.of("910", "USD"), accountMovementDisplay = "910.00 USD",
        deductions = listOf(
            TransactionDeductionRow("Income tax", DeductionTiming.NOW, Money.of("50", "USD"), "50.00 USD", null),
            TransactionDeductionRow("Pension fund", DeductionTiming.NOW, Money.of("40", "USD"), "40.00 USD", null),
        ),
    )

    private val transferRow = TransactionRow(
        id = 7, date = day, type = TransactionRowType.TRANSFER,
        accountName = "Dollar account", destAccountName = "Euro account",
        categoryLabel = null,
        amount = Money.of("100", "USD"), destAmount = Money.of("90", "EUR"),
        amountDisplay = "100.00 USD", destAmountDisplay = "90.00 EUR", note = null,
    )

    private fun loaded(row: TransactionRow) = TransactionDetailUiState(isLoading = false, row = row)

    private class Calls {
        var back = 0
        var retry = 0
        var edit = 0
        var delete = 0
        var undo = 0
        var confirm = 0
    }

    @Composable
    private fun Screen(state: TransactionDetailUiState, calls: Calls) {
        TransactionDetailContent(
            state = state,
            onBack = { calls.back++ },
            onRetry = { calls.retry++ },
            onEdit = { calls.edit++ },
            onDelete = { calls.delete++ },
            onUndoDelete = { calls.undo++ },
            onConfirmDelete = { calls.confirm++ },
        )
    }

    private fun show(state: TransactionDetailUiState, calls: Calls = Calls()): Calls {
        compose.setContent { MyWalletTheme(dynamicColor = false) { Screen(state, calls) } }
        return calls
    }

    private fun tag(name: String) = compose.onNodeWithTag(name)

    private fun assertAbsent(vararg tags: String) = tags.forEach { tag(it).assertDoesNotExist() }

    @Test
    fun loadingShowsTheLoadingIndicatorAndNoAmount() {
        show(TransactionDetailUiState())

        tag("transaction-detail-loading").assertExists()
        assertAbsent("transaction-detail-hero-amount", "transaction-detail-not-found", "transaction-detail-retry")
    }

    @Test
    fun anExpenseShowsAmountCategoryAccountAndNoteWithoutFeeOrDeductions() {
        show(loaded(expenseRow))

        tag("transaction-detail-hero-amount").assertIsDisplayed().assertTextContains("50.00 USD", substring = true)
        tag("transaction-detail-category").assertTextContains("Groceries", substring = true)
        tag("transaction-detail-account").assertTextContains("Cash wallet", substring = true)
        tag("transaction-detail-note").assertTextContains("weekly shop", substring = true)
        assertAbsent("transaction-detail-fee", "transaction-detail-deduction-0", "transaction-detail-dest-account")
    }

    @Test
    fun anIncomeShowsGrossAccountMovementAndBothSavedDeductions() {
        show(loaded(incomeRow))

        tag("transaction-detail-gross").assertTextContains("1,000.00 USD", substring = true)
        tag("transaction-detail-movement").assertTextContains("910.00 USD", substring = true)
        tag("transaction-detail-deduction-0").assertTextContains("Income tax", substring = true)
        tag("transaction-detail-deduction-0").assertTextContains("50.00 USD", substring = true)
        tag("transaction-detail-deduction-1").assertTextContains("Pension fund", substring = true)
        tag("transaction-detail-deduction-1").assertTextContains("40.00 USD", substring = true)
        tag("transaction-detail-deduction-2").assertDoesNotExist()
    }

    @Test
    fun grossAndAccountMovementAreSeparateNodesWithDifferentText() {
        show(loaded(incomeRow))

        val gross = tag("transaction-detail-gross").fetchSemanticsNode()
        val movement = tag("transaction-detail-movement").fetchSemanticsNode()
        assertTrue(gross.id != movement.id)
        tag("transaction-detail-gross").assert(!hasText("910.00 USD", substring = true))
        tag("transaction-detail-movement").assert(!hasText("1,000.00 USD", substring = true))
    }

    @Test
    fun aTransferShowsBothAccountsAndTheDestinationAmount() {
        show(loaded(transferRow))

        tag("transaction-detail-account").assertTextContains("Dollar account", substring = true)
        tag("transaction-detail-dest-account").assertTextContains("Euro account", substring = true)
        tag("transaction-detail-movement").assertTextContains("90.00 EUR", substring = true)
        tag("transaction-detail-hero-amount").assertTextContains("100.00 USD", substring = true)
    }

    @Test
    fun theFeeRowShowsOnlyWhenTheEntryHasAFee() {
        show(loaded(expenseRow.copy(feeDisplay = "2.00 USD")))

        tag("transaction-detail-fee").assertTextContains("2.00 USD", substring = true)
    }

    @Test
    fun noFeeRowShowsWhenFeeDisplayIsNull() {
        show(loaded(expenseRow.copy(feeDisplay = null)))

        assertAbsent("transaction-detail-fee")
    }

    @Test
    fun editReportsToTheCaller() {
        val calls = show(loaded(expenseRow))

        tag("transaction-detail-edit").performClick()

        compose.runOnIdle { assertEquals(1, calls.edit) }
    }

    @Test
    fun deleteAsksForConfirmationNamingTheEntryBeforeReporting() {
        val calls = show(loaded(expenseRow))

        tag("transaction-detail-delete").performScrollTo().performClick()

        tag("transaction-detail-delete-confirm").assertExists()
        compose.onNode(isDialog()).assert(hasAnyDescendant(hasText("Groceries", substring = true)))
        compose.runOnIdle { assertEquals(0, calls.delete) }

        tag("transaction-detail-delete-confirm").performClick()

        compose.runOnIdle { assertEquals(1, calls.delete) }
    }

    @Test
    fun cancellingTheDeleteConfirmationDismissesItWithoutDeleting() {
        val calls = show(loaded(expenseRow))
        tag("transaction-detail-delete").performScrollTo().performClick()

        tag("transaction-detail-delete-cancel").performClick()

        tag("transaction-detail-delete-confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls.delete) }
    }

    @Test
    fun notFoundAndLoadFailureAreDifferentNodesAndShowNoAmount() {
        show(TransactionDetailUiState(isLoading = false, notFound = true))
        tag("transaction-detail-not-found").assertExists()
        assertAbsent("transaction-detail-retry", "transaction-detail-hero-amount")
    }

    @Test
    fun aLoadFailureOffersRetryAndShowsNoAmountOrNotFound() {
        val calls = show(TransactionDetailUiState(isLoading = false, loadFailure = UiMessage.DataUnreadable))

        assertAbsent("transaction-detail-not-found", "transaction-detail-hero-amount")
        tag("transaction-detail-retry").performClick()

        compose.runOnIdle { assertEquals(1, calls.retry) }
    }

    @Test
    fun aRefusedDeleteShowsTheErrorAndKeepsTheEntryDetails() {
        show(loaded(expenseRow).copy(deleteError = UiMessage.Unexpected))

        tag("transaction-detail-delete-error").assertExists()
        tag("transaction-detail-hero-amount").assertTextContains("50.00 USD", substring = true)
        tag("transaction-detail-account").assertTextContains("Cash wallet", substring = true)
    }

    @Test
    fun noDeleteErrorShowsWhenNothingWasRefused() {
        show(loaded(expenseRow))

        assertAbsent("transaction-detail-delete-error", "transaction-detail-undo")
    }

    @Test
    fun anUndoableDeleteOffersUndoThatReportsToTheCaller() {
        val removed = UndoBundle(
            entries = listOf(
                Transaction.Expense(
                    id = 5, accountId = 1, amount = Money.of("50", "USD"), categoryId = 20, date = day,
                ),
            ),
        )
        val calls = show(loaded(expenseRow).copy(undoableDelete = removed))

        tag("transaction-detail-undo").performClick()

        compose.runOnIdle { assertEquals(1, calls.undo) }
    }

    @Test
    fun theTwelveDigitAmountAndDeleteStayReachableAtThreeSixtyDpAndDoubleFontScale() {
        val big = expenseRow.copy(
            amount = Money.of("123456789012", "KWD"), amountDisplay = "123,456,789,012.000 KWD",
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).height(640.dp)) { Screen(loaded(big), Calls()) }
                }
            }
        }

        tag("transaction-detail-hero-amount").assertIsDisplayed()
            .assertTextContains("123,456,789,012.000 KWD", substring = true)
        tag("transaction-detail-delete").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun editAndDeleteHaveAtLeast48dpTouchTargets() {
        show(loaded(expenseRow))

        val minimumPx = with(compose.density) { 48.dp.toPx() }
        listOf("transaction-detail-edit", "transaction-detail-delete").forEach { name ->
            val node = if (name.endsWith("delete")) tag(name).performScrollTo() else tag(name)
            val touch = node.fetchSemanticsNode().touchBoundsInRoot
            assertTrue("$name touch height", touch.height >= minimumPx - 0.5f)
            assertTrue("$name touch width", touch.width >= minimumPx - 0.5f)
        }
    }

    @Test
    fun rightToLeftKeepsTheAmountTextIntact() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyWalletTheme(dynamicColor = false) { Screen(loaded(expenseRow), Calls()) }
            }
        }

        // The money text is bidi isolated, so the digits and the code read in the same order.
        tag("transaction-detail-hero-amount").assertIsDisplayed().assertTextContains("50.00 USD", substring = true)
    }
}
