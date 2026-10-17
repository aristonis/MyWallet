package org.aristonis.mywallet.ui.transaction

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.aristonis.mywallet.ui.window.DateWindowActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.TransactionFilter
import org.aristonis.mywallet.domain.model.TypeGroup
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Test

class TransactionFilterBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allStartsSelectedAndTappingTransferSelectsOnlyTransfer() {
        val filter = mutableStateOf(TransactionFilter())
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                TransactionFilterBar(filter.value, { filter.value = filter.value.copy(types = if (it == TypeGroup.ALL) emptySet() else filter.value.types.let { selected -> if (it in selected) selected - it else selected + it }) }, {})
            }
        }
        compose.onNodeWithText("All").assertIsSelected()
        compose.onNodeWithTag("transaction-type-filters").performScrollToNode(hasText("Transfer"))
        compose.onNodeWithText("Transfer").performClick()
        compose.onNodeWithText("Transfer").assertIsSelected()
        compose.onNodeWithTag("transaction-type-filters").performScrollToNode(hasText("All"))
        compose.onNodeWithText("All").assertIsNotSelected()
    }

    @Test fun incomeAndExpenseCanBothStaySelected() {
        val filter = mutableStateOf(TransactionFilter())
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                TransactionFilterBar(filter.value, { type ->
                    filter.value = filter.value.copy(types = if (type == TypeGroup.ALL) emptySet()
                        else if (type in filter.value.types) filter.value.types - type else filter.value.types + type)
                }, {})
            }
        }
        compose.onNodeWithTag("transaction-type-filters").performScrollToNode(hasText("Income"))
        compose.onNodeWithText("Income").performClick()
        compose.onNodeWithTag("transaction-type-filters").performScrollToNode(hasText("Expense"))
        compose.onNodeWithText("Expense").performClick().assertIsSelected()
        compose.onNodeWithTag("transaction-type-filters").performScrollToNode(hasText("Income"))
        compose.onNodeWithText("Income").assertIsSelected()
    }

    @Test fun filterBadgeShowsBothSheetTerms() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                TransactionFilterBar(TransactionFilter(accountIds = setOf(1, 3), categoryIds = setOf(2, 4)), {}, {})
            }
        }
        compose.onNodeWithText("2").assertIsDisplayed()
    }
    @Test fun sheetListsChildrenUnderParentAndClearAllResetsSelection() {
        val filter = mutableStateOf(TransactionFilter(setOf(TypeGroup.EXPENSE), categoryIds = setOf(2)))
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                TransactionFilterSheet(filter.value, emptyList(), listOf(
                    Category(1, "Bills", CategoryKind.EXPENSE),
                    Category(2, "Internet", CategoryKind.EXPENSE, parentId = 1),
                ), {}, {}, { filter.value = filter.value.copy(categoryIds = filter.value.categoryIds.let { ids -> if (it in ids) ids - it else ids + it }) },
                    {}, { filter.value = TransactionFilter() }, {})
            }
        }
        compose.onNodeWithText("Bills › Internet").assertIsDisplayed()
        compose.onNodeWithText("Clear all").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(TransactionFilter(), filter.value) }
    }

    @Test fun chipsRemainReachableAtLargeTextInRtl() {
        val filter = mutableStateOf(TransactionFilter())
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    TransactionFilterBar(filter.value, { filter.value = filter.value.copy(types = if (it == TypeGroup.ALL) emptySet() else filter.value.types.let { selected -> if (it in selected) selected - it else selected + it }) }, {})
                }
            }
        }
        compose.onNodeWithTag("transaction-type-filters").performScrollToNode(hasText("Debts"))
        compose.onNodeWithText("Debts").performClick().assertIsSelected()
        compose.onNodeWithTag("transaction-type-filters").performScrollToNode(hasText("All"))
        compose.onNodeWithText("All").performClick().assertIsSelected()
    }

    @Test fun emptyTransferNamesTheSelectedType() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                TransactionsListContent(
                    state = TransactionsUiState(isLoading = false, filter = TransactionFilter(setOf(TypeGroup.TRANSFER))),
                    windowActions = DateWindowActions.None,
                    onAddTransaction = {}, onOpenTransaction = {}, onShowSavedEntry = {},
                    onSavedEntryMessageDone = {}, onRetry = {},
                )
            }
        }
        compose.onNodeWithText("No transfers in this period").assertIsDisplayed()
    }

    @Test fun emptyCategoryNamesTheSelectedCategory() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                TransactionsListContent(
                    state = TransactionsUiState(isLoading = false, filter = TransactionFilter(categoryIds = setOf(30)),
                        filterCategories = listOf(Category(30, "Tax", CategoryKind.EXPENSE))),
                    windowActions = DateWindowActions.None,
                    onAddTransaction = {}, onOpenTransaction = {}, onShowSavedEntry = {},
                    onSavedEntryMessageDone = {}, onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Nothing in Tax in this period").assertIsDisplayed()
    }

    @Test fun feeMatchedExpenseShowsTheMatchedAmountOnce() {
        val date = java.time.LocalDate.of(2026, 10, 17)
        val amount = org.aristonis.mywallet.domain.model.Money.of("5", "USD")
        val fee = org.aristonis.mywallet.domain.model.Money.of("0.25", "USD")
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                TransactionRowItem(TransactionRow(
                    id = 1, date = date, type = TransactionRowType.EXPENSE, accountName = "Cash",
                    destAccountName = null, categoryLabel = org.aristonis.mywallet.ui.CategoryLabel.Named("Food"),
                    amount = amount, destAmount = null, amountDisplay = "5.00 USD", destAmountDisplay = null,
                    note = null, feeDisplay = "0.25 USD",
                    matchedParts = listOf(TransactionMatchedPart(org.aristonis.mywallet.ui.CategoryLabel.Named("Fees"), fee, "0.25 USD")),
                ), {})
            }
        }
        compose.onAllNodes(hasText("Fees ", substring = true), useUnmergedTree = true).assertCountEquals(1)
    }

}
