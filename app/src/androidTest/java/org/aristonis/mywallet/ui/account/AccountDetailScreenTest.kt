package org.aristonis.mywallet.ui.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.transaction.TransactionRow
import org.aristonis.mywallet.ui.transaction.TransactionRowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AccountDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    private val header = AccountDetailHeader(
        id = 1, name = "Checking", typeKey = "cash", currencyCode = "USD",
        archived = false, nativeDisplay = "125.00 USD",
    )

    private val creditHeader = header.copy(
        name = "Visa", typeKey = "credit_card", nativeDisplay = "-300.00 USD",
        credit = CreditDetail(owedDisplay = "300.00 USD", limitDisplay = "1,000.00 USD", availableDisplay = "700.00 USD"),
    )

    private fun entry(id: Long) = TransactionRow(
        id = id, date = LocalDate.of(2026, 1, 1).plusDays(id), type = TransactionRowType.EXPENSE,
        accountName = "Checking", destAccountName = null, categoryLabel = null,
        amount = Money.of("5", "USD"), destAmount = null,
        amountDisplay = "5.00 USD", destAmountDisplay = null, note = "Entry $id",
    )

    private fun populated(rows: Int = 3, head: AccountDetailHeader = header) = AccountDetailUiState(
        isLoading = false, header = head, entries = (1L..rows).map(::entry),
    )

    private fun show(
        state: AccountDetailUiState,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onAddTransaction: () -> Unit = {},
        onPayCard: () -> Unit = {},
        onEditAccount: () -> Unit = {},
        onOpenEntry: (Long) -> Unit = {},
        onLoadMore: () -> Unit = {},
        onManageRates: () -> Unit = {},
    ) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                AccountDetailContent(
                    state = state, onBack = onBack, onRetry = onRetry, onAddTransaction = onAddTransaction,
                    onPayCard = onPayCard, onEditAccount = onEditAccount, onOpenEntry = onOpenEntry,
                    onLoadMore = onLoadMore, onManageRates = onManageRates,
                )
            }
        }
    }

    private fun showNarrowAtDoubleText(state: AccountDetailUiState) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).height(640.dp)) {
                        AccountDetailContent(
                            state = state, onBack = {}, onRetry = {}, onAddTransaction = {}, onPayCard = {},
                            onEditAccount = {}, onOpenEntry = {}, onLoadMore = {}, onManageRates = {},
                        )
                    }
                }
            }
        }
    }

    /** Brings [tag] into view whether the page is a lazy list or a plain scrolling column. */
    private fun scrollTo(tag: String) {
        val isLazy = compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty()
        if (isLazy) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        } else {
            compose.onNodeWithTag(tag).performScrollTo()
        }
    }

    private fun assertNoZeroBalance() {
        compose.onNodeWithTag("account-detail-balance").assertDoesNotExist()
        compose.onAllNodesWithText("0.00", substring = true).assertCountEquals(0)
    }

    @Test fun populatedAccountShowsItsBalanceAndEveryEntryRow() {
        show(populated())

        compose.onNodeWithTag("account-detail-balance").assertIsDisplayed()
        compose.onAllNodesWithText("125.00 USD", substring = true).onFirst().assertIsDisplayed()
        (1L..3L).forEach {
            scrollTo("account-detail-entry-$it")
            compose.onNodeWithTag("account-detail-entry-$it").assertIsDisplayed()
        }
        compose.onNodeWithTag("account-detail-no-entries").assertDoesNotExist()
    }

    @Test fun tappingAnEntryRowOpensThatEntry() {
        var opened: Long? = null
        show(populated(), onOpenEntry = { opened = it })

        compose.onNodeWithTag("account-detail-entry-2").performClick()

        compose.runOnIdle { assertEquals(2L, opened) }
    }

    @Test fun entryRowsKeepAMinimumTouchTargetHeight() {
        show(populated())

        (1L..3L).forEach {
            scrollTo("account-detail-entry-$it")
            compose.onNodeWithTag("account-detail-entry-$it").assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun emptyAccountSaysItHasNoEntries() {
        show(populated(rows = 0))

        compose.onNodeWithTag("account-detail-no-entries").assertIsDisplayed()
        compose.onNodeWithTag("account-detail-balance").assertIsDisplayed()
    }

    @Test fun creditAccountShowsLimitAvailableAndPayCard() {
        show(populated(head = creditHeader))

        compose.onNodeWithTag("account-detail-credit-limit").assertExists()
        compose.onNodeWithTag("account-detail-available").assertExists()
        compose.onNodeWithTag("account-detail-over-limit").assertDoesNotExist()
        compose.onNodeWithTag("account-detail-pay-card").assertExists()
    }

    @Test fun creditAccountOverItsLimitSaysSo() {
        val over = creditHeader.copy(
            credit = CreditDetail("1,200.00 USD", "1,000.00 USD", "0.00 USD", overLimit = true),
        )
        show(populated(head = over))

        compose.onNodeWithTag("account-detail-over-limit").assertExists()
    }

    @Test fun payCardIsOfferedOnlyForCreditAccounts() {
        show(populated())

        compose.onNodeWithTag("account-detail-pay-card").assertDoesNotExist()
        compose.onNodeWithTag("account-detail-credit-limit").assertDoesNotExist()
    }

    @Test fun payCardCallsItsCallback() {
        var paid = 0
        show(populated(head = creditHeader), onPayCard = { paid++ })

        compose.onNodeWithTag("account-detail-pay-card").performClick()

        compose.runOnIdle { assertEquals(1, paid) }
    }

    @Test fun anArchivedAccountOffersEditButNoNewEntries() {
        show(populated(head = creditHeader.copy(archived = true)))

        compose.onNodeWithTag("account-detail-edit").assertIsDisplayed()
        compose.onNodeWithTag("account-detail-add").assertDoesNotExist()
        compose.onNodeWithTag("account-detail-pay-card").assertDoesNotExist()
    }

    @Test fun addTransactionCallsItsCallback() {
        var added = 0
        show(populated(), onAddTransaction = { added++ })

        compose.onNodeWithTag("account-detail-add").performClick()

        compose.runOnIdle { assertEquals(1, added) }
    }

    @Test fun editAccountCallsItsCallback() {
        var edited = 0
        show(populated(), onEditAccount = { edited++ })

        compose.onNodeWithTag("account-detail-edit").performClick()

        compose.runOnIdle { assertEquals(1, edited) }
    }

    @Test fun backCallsItsCallback() {
        var backed = 0
        show(populated(), onBack = { backed++ })

        compose.onNodeWithContentDescription("Back").performClick()

        compose.runOnIdle { assertEquals(1, backed) }
    }

    @Test fun baseBalanceIsShownForAnAccountInAnotherCurrency() {
        show(populated(head = header.copy(currencyCode = "EUR", nativeDisplay = "50.00 EUR", baseDisplay = "55.00 USD")))

        compose.onNodeWithTag("account-detail-base-balance").assertExists()
        compose.onNodeWithTag("account-detail-missing-rate").assertDoesNotExist()
    }

    @Test fun missingRateOffersTheRatesLinkInsteadOfABaseBalance() {
        var opened = 0
        show(
            populated(head = header.copy(currencyCode = "EUR", nativeDisplay = "50.00 EUR", missingRate = true)),
            onManageRates = { opened++ },
        )

        compose.onNodeWithTag("account-detail-missing-rate").assertExists()
        compose.onNodeWithTag("account-detail-base-balance").assertDoesNotExist()
        compose.onAllNodesWithText("rates", substring = true, ignoreCase = true)
            .onFirst().assertExists()
        compose.onAllNodes(hasText("rates", substring = true, ignoreCase = true) and hasClickAction())
            .onFirst().performClick()

        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun loadingShowsNoBalance() {
        show(AccountDetailUiState())

        compose.onNodeWithTag("account-detail-loading").assertExists()
        assertNoZeroBalance()
        compose.onNodeWithTag("account-detail-not-found").assertDoesNotExist()
        compose.onNodeWithTag("account-detail-retry").assertDoesNotExist()
    }

    @Test fun notFoundShowsNoBalanceAndNoRetry() {
        show(AccountDetailUiState(isLoading = false, notFound = true))

        compose.onNodeWithTag("account-detail-not-found").assertExists()
        assertNoZeroBalance()
        compose.onNodeWithTag("account-detail-retry").assertDoesNotExist()
        compose.onNodeWithTag("account-detail-loading").assertDoesNotExist()
    }

    @Test fun readFailureIsADifferentNodeFromNotFoundAndShowsNoBalance() {
        show(AccountDetailUiState(isLoading = false, loadFailure = UiMessage.DataUnreadable))

        compose.onNodeWithTag("account-detail-retry").assertExists()
        compose.onNodeWithTag("account-detail-not-found").assertDoesNotExist()
        assertNoZeroBalance()
    }

    @Test fun retryCallsItsCallback() {
        var retried = 0
        show(AccountDetailUiState(isLoading = false, loadFailure = UiMessage.DataUnreadable), onRetry = { retried++ })

        compose.onNodeWithTag("account-detail-retry").performClick()

        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test fun loadMoreIsOfferedWhenThereAreMoreEntriesAndCallsItsCallback() {
        var loaded = 0
        show(populated().copy(hasMore = true), onLoadMore = { loaded++ })

        scrollTo("account-detail-load-more")
        compose.onNodeWithTag("account-detail-load-more").performClick()

        compose.runOnIdle { assertTrue(loaded >= 1) }
    }

    @Test fun loadMoreIsAbsentWhenEverythingIsLoaded() {
        show(populated())

        compose.onNodeWithTag("account-detail-load-more").assertDoesNotExist()
    }

    @Test fun lastEntryCanBeScrolledToAtNarrowWidthAndDoubleTextSize() {
        showNarrowAtDoubleText(populated(rows = 12))

        scrollTo("account-detail-entry-12")

        compose.onNodeWithTag("account-detail-entry-12").assertIsDisplayed()
    }

    @Test fun atDoubleTextTheActionsFollowTheEntriesInsteadOfCoveringThem() {
        showNarrowAtDoubleText(populated(rows = 12))

        // Stacked at this size, the buttons would take about half the screen if they stayed pinned.
        compose.onNodeWithTag("account-detail-add").assertDoesNotExist()
        scrollTo("account-detail-add")

        compose.onNodeWithTag("account-detail-add").assertIsDisplayed()
        compose.onNodeWithTag("account-detail-edit").assertExists()
    }

    @Test fun twelveDigitNativeAmountIsFullyVisibleAtNarrowWidthAndDoubleTextSize() {
        val amount = "123,456,789,012.00 USD"
        showNarrowAtDoubleText(populated(head = header.copy(nativeDisplay = amount)))

        compose.onNodeWithTag("account-detail-balance").assertIsDisplayed()
        val text = compose.onNode(hasText(amount), useUnmergedTree = true)
        text.assertIsDisplayed()
        val bounds = text.getUnclippedBoundsInRoot()
        assertTrue("the amount starts inside the screen", bounds.left >= 0.dp)
        assertTrue("the amount ends inside 360dp", bounds.right <= 360.dp)
        val layouts = mutableListOf<TextLayoutResult>()
        text.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        layouts.firstOrNull()?.let { assertFalse("the amount is not truncated", it.hasVisualOverflow) }
    }
}
