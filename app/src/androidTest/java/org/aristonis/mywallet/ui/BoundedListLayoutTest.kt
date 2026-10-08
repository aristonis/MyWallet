package org.aristonis.mywallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.account.ManageAccountRow
import org.aristonis.mywallet.ui.account.ManageAccountsContent
import org.aristonis.mywallet.ui.account.ManageAccountsUiState
import org.aristonis.mywallet.ui.home.AccountRow
import org.aristonis.mywallet.ui.home.HomeContent
import org.aristonis.mywallet.ui.home.HomeUiState
import org.aristonis.mywallet.ui.home.NetWorthState
import org.aristonis.mywallet.ui.rates.ManageRatesContent
import org.aristonis.mywallet.ui.rates.ManageRatesUiState
import org.aristonis.mywallet.ui.rates.RateRow
import org.aristonis.mywallet.ui.reports.CategoryRow
import org.aristonis.mywallet.ui.reports.ReportsContent
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.reports.ReportsData
import org.aristonis.mywallet.ui.reports.ReportsUiState
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.math.BigDecimal

/**
 * A `LazyColumn` with nothing bounding its height asks for the height of every row it holds. A
 * `Column` hands that out, the list runs past the bottom of the window, and the rows below the fold
 * become unreachable — the list will not scroll, because as far as it knows it is entirely visible.
 *
 * Each of these renders a screen with more rows than fit and then scrolls to the last one. That is
 * the check that matters: a populated screen whose final row cannot be reached is a screen where the
 * user has lost an account, a rate or a category.
 */
@RunWith(AndroidJUnit4::class)
class BoundedListLayoutTest {

    @get:Rule val compose = createComposeRule()

    private val rowCount = 30

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { MyWalletTheme(dynamicColor = false) { content() } }
    }

    /**
     * Scrolls whichever list on the screen can scroll. Some of these screens have a second scrollable
     * (a segmented row), so the list is picked by looking for the row rather than by position.
     */
    private fun scrollToLast(text: String) {
        val scrollables = compose.onAllNodes(hasScrollToNodeAction())
        scrollables.fetchSemanticsNodes().indices.forEach { index ->
            runCatching { scrollables[index].performScrollToNode(hasText(text)) }
                .onSuccess { return }
        }
        error("no scrollable list could reach \"$text\"")
    }

    @Test
    fun homeReachesItsLastAccount() {
        val accounts = (1..rowCount).map { index ->
            AccountRow(
                account = Account(
                    id = index.toLong(),
                    name = "Account $index",
                    typeKey = "cash",
                    currencyCode = "USD",
                    openingBalance = Money.zero("USD"),
                ),
                native = Money.of("$index", "USD"),
                base = Money.of("$index", "USD"),
                nativeDisplay = "$index.00 USD",
                baseDisplay = "$index.00 USD",
            )
        }
        show {
            HomeContent(
                state = HomeUiState(
                    netWorth = NetWorthState.Amount("465.00 USD"),
                    accounts = accounts,
                    baseCurrencyCode = "USD",
                ),
                onAddTransaction = {},
                onAddAccount = {},
                onManageAccounts = {},
                onManageRates = {},
                onSettings = {},
            )
        }

        compose.onNodeWithText("Account 1").assertIsDisplayed()
        scrollToLast("Account $rowCount")
        compose.onNodeWithText("Account $rowCount").assertIsDisplayed()
    }

    /** Home is one list, so the hero scrolls away with the accounts rather than pinning them down. */
    @Test
    fun homeScrollsItsHeroAwayWithTheAccounts() {
        show {
            HomeContent(
                state = HomeUiState(
                    netWorth = NetWorthState.Amount("465.00 USD"),
                    accounts = (1..rowCount).map { index ->
                        AccountRow(
                            account = Account(
                                id = index.toLong(),
                                name = "Account $index",
                                typeKey = "cash",
                                currencyCode = "USD",
                                openingBalance = Money.zero("USD"),
                            ),
                            native = Money.of("$index", "USD"),
                            base = Money.of("$index", "USD"),
                            nativeDisplay = "$index.00 USD",
                            baseDisplay = "$index.00 USD",
                        )
                    },
                    baseCurrencyCode = "USD",
                ),
                onAddTransaction = {},
                onAddAccount = {},
                onManageAccounts = {},
                onManageRates = {},
                onSettings = {},
            )
        }

        compose.onNodeWithText("465.00 USD").assertIsDisplayed()
        scrollToLast("Account $rowCount")
        compose.onNodeWithText("465.00 USD").assertDoesNotExist()
    }

    @Test
    fun manageAccountsReachesItsLastRow() {
        val rows = (1..rowCount).map { index ->
            ManageAccountRow(
                account = Account(
                    id = index.toLong(),
                    name = "Account $index",
                    typeKey = "cash",
                    currencyCode = "USD",
                    openingBalance = Money.zero("USD"),
                ),
                hasTransactions = false,
            )
        }
        show {
            ManageAccountsContent(
                state = ManageAccountsUiState(rows = rows, isLoading = false),
                onArchive = {},
                onUnarchive = {},
                onDelete = {},
                onEdit = {},
                onAddAccount = {},
                onDone = {},
            )
        }

        compose.onNodeWithText("Account 1").assertIsDisplayed()
        scrollToLast("Account $rowCount")
        compose.onNodeWithText("Account $rowCount").assertIsDisplayed()
    }

    @Test
    fun manageRatesReachesItsLastRow() {
        val rows = (1..rowCount).map { index ->
            RateRow(
                currencyCode = "C$index",
                symbol = "$index",
                currentRate = BigDecimal("1.10"),
                input = "1.10",
            )
        }
        show {
            ManageRatesContent(
                state = ManageRatesUiState(baseCurrencyCode = "USD", rows = rows, isLoading = false),
                onInputChanged = { _, _ -> },
                onSave = {},
                onDone = {},
            )
        }

        compose.onNodeWithText("C1 (1)").assertIsDisplayed()
        scrollToLast("C$rowCount ($rowCount)")
        compose.onNodeWithText("C$rowCount ($rowCount)").assertIsDisplayed()
    }

    /** Tracking has a segmented row above the list, which must stay put while the breakdown scrolls. */
    @Test
    fun trackingReachesItsLastCategoryAndKeepsThePeriodControl() {
        val categories = (1..rowCount).map { index ->
            CategoryRow(
                id = index.toLong(),
                label = CategoryLabel.Named("Category $index"),
                totalDisplay = "$index.00 USD",
                subCategories = emptyList(),
            )
        }
        show {
            ReportsContent(
                state = ReportsUiState(
                    window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 7, 15)),
                    data = ReportsData.Ready(
                        incomeDisplay = "100.00 USD",
                        expenseDisplay = "40.00 USD",
                        netDisplay = "60.00 USD",
                        incomeCategories = emptyList(),
                        expenseCategories = categories,
                    ),
                ),
                windowActions = DateWindowActions.None,
            )
        }

        compose.onNodeWithText("Category 1").assertIsDisplayed()
        scrollToLast("Category $rowCount")
        compose.onNodeWithText("Category $rowCount").assertIsDisplayed()
        compose.onNodeWithText("Month").assertIsDisplayed()
        compose.onNodeWithText("July 2026").assertIsDisplayed()
    }
}
