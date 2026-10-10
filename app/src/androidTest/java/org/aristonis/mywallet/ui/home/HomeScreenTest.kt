package org.aristonis.mywallet.ui.home

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
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
        // Net worth cannot be worked out either; a spinner next to the error would never stop.
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
    }

    @Test
    fun debtsCanBeOpenedEvenWhenThereAreNoAccounts() {
        val openings = AtomicInteger()
        show(HomeUiState(isLoading = false), onDebts = { openings.incrementAndGet() })

        compose.onNodeWithText(text(R.string.debts_title)).assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(1, openings.get()) }
    }
}
