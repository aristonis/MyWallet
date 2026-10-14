package org.aristonis.mywallet.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Home before its first read: nothing is known yet, so it must not say the wallet is empty. */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun show(state: HomeUiState, onDebts: () -> Unit = {}) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                HomeContent(
                    state = state,
                    onAddTransaction = {},
                    onAddAccount = {},
                    onManageAccounts = {},
                    onManageRates = {},
                    onSettings = {},
                    onDebts = onDebts,
                    onRetry = {},
                )
            }
        }
    }

    @Test
    fun loadingShowsNoEmptyState() {
        show(HomeUiState())

        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithText(text(R.string.home_no_accounts)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.home_create_first_account)).assertDoesNotExist()
    }

    @Test
    fun aWalletWithNoAccountsInvitesTheFirstOne() {
        show(HomeUiState(isLoading = false))

        compose.onNodeWithText(text(R.string.home_no_accounts)).assertIsDisplayed()
    }

    @Test
    fun aFailedReadSaysSoInsteadOfInvitingAFirstAccount() {
        show(HomeUiState(isLoading = false, loadFailed = true))

        compose.onNodeWithText(text(R.string.home_data_unreadable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_no_accounts)).assertDoesNotExist()
        // The balance cannot be worked out either; a spinner next to the error would never stop.
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
    }

    @Test
    fun debtsCanBeOpenedEvenWhenThereAreNoAccounts() {
        val openings = AtomicInteger()
        show(HomeUiState(isLoading = false), onDebts = { openings.incrementAndGet() })

        compose.onNodeWithText(text(R.string.debts_title)).assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(1, openings.get()) }
    }

    @Test
    fun finalSummaryTextClearsAddActionAtTwoHundredPercentInRtl() {
        val state = HomeUiState(
            summary = HomeSummaryState.Amounts(
                pocketDisplay = "100.00 USD",
                owedToMeDisplay = "20.00 USD",
                iOweDisplay = "30.00 USD",
                balanceDisplay = "90.00 USD",
            ),
            isLoading = false,
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    HomeContent(state, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }

        val owed = compose.onNodeWithText(text(R.string.home_i_owe)).performScrollTo().getBoundsInRoot()
        val add = compose.onNodeWithText(text(R.string.home_add_transaction)).getBoundsInRoot()
        assertTrue("The I owe text must clear the Add action", owed.bottom <= add.top || owed.top >= add.bottom)
    }

    @Test
    fun finalAccountActionClearsAddActionAndRemainsClickableAtTwoHundredPercentInRtl() {
        val rateOpens = AtomicInteger()
        val state = HomeUiState(
            summary = HomeSummaryState.Amounts("100.00 USD", "0.00 USD", "0.00 USD", "100.00 USD"),
            accounts = listOf(
                AccountRow(
                    account = Account(1, "Final Account", "bank", "EUR", Money.of("987.65", "EUR")),
                    native = Money.of("987.65", "EUR"),
                    base = null,
                    nativeDisplay = "987.65 EUR",
                    baseDisplay = null,
                ),
            ),
            isLoading = false,
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    HomeContent(state, {}, {}, {}, { rateOpens.incrementAndGet() }, {}, {}, {})
                }
            }
        }

        val actionLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.home_account_missing_rate, "EUR")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        val accountAction = compose.onNodeWithText(actionLabel).assertIsDisplayed()
        val actionBounds = accountAction.getBoundsInRoot()
        val addBounds = compose.onNodeWithText(text(R.string.home_add_transaction)).getBoundsInRoot()
        assertTrue("The final account action $actionBounds must clear Add $addBounds", actionBounds.bottom <= addBounds.top ||
            actionBounds.top >= addBounds.bottom)
        accountAction.performClick()
        compose.runOnIdle { assertEquals(1, rateOpens.get()) }
    }

    @Test
    fun longNativeBalanceMovesBelowTheAccountNameAtNarrowWidth() {
        val name = "Everyday wallet"
        val amount = "12,345,678,901.00 USD"
        val state = HomeUiState(
            summary = HomeSummaryState.Amounts("100.00 USD", "0.00 USD", "0.00 USD", "100.00 USD"),
            accounts = listOf(
                AccountRow(
                    account = Account(1, name, "cash", "USD", Money.of("12345678901", "USD")),
                    native = Money.of("12345678901", "USD"),
                    base = Money.of("12345678901", "USD"),
                    nativeDisplay = amount,
                    baseDisplay = amount,
                ),
            ),
            isLoading = false,
        )
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                Box(Modifier.width(360.dp)) {
                    HomeContent(state, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }

        // The account row is one tappable item, so its texts only have their own bounds unmerged.
        val nameBounds = compose.onNodeWithText(name, useUnmergedTree = true).performScrollTo().getBoundsInRoot()
        val amountBounds = compose.onNodeWithText(amount, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("The full balance should use a line below the account name",
            amountBounds.top >= nameBounds.bottom)
    }
}
