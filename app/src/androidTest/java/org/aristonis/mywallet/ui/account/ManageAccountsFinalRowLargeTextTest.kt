package org.aristonis.mywallet.ui.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManageAccountsFinalRowLargeTextTest {
    @get:Rule val compose = createComposeRule()

    @Test fun threeActiveAccountsUseCompactGroupedRowsAtPhoneWidth() {
        val rows = (1L..3L).map { id ->
            ManageAccountRow(
                Account(id, "Account $id", "bank", "USD", Money.zero("USD")),
                hasTransactions = true,
                balance = Money.zero("USD"),
                balanceDisplay = "0.00 USD",
            )
        }
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    ManageAccountsContent(
                        state = ManageAccountsUiState(rows = rows, isLoading = false),
                        onArchive = {}, onUnarchive = {}, onDelete = {}, onEdit = {},
                        onAddAccount = {}, onDone = {}, onRetry = {},
                    )
                }
            }
        }

        val first = compose.onNodeWithTag("account-row-1").getUnclippedBoundsInRoot()
        val third = compose.onNodeWithTag("account-row-3").getUnclippedBoundsInRoot()
        assertTrue(
            "Three normal account rows should fit in a 280dp group at 360dp width",
            third.bottom - first.top <= 280.dp,
        )
    }

    @Test fun finalAccountMenuRemainsReachableAtTwoHundredPercentInRtl() {
        val rows = (1L..12L).map { id ->
            ManageAccountRow(
                Account(id, if (id == 12L) "Final account" else "Account $id", "bank", "USD", Money.of("0", "USD")),
                hasTransactions = true,
                balance = Money.zero("USD"),
                balanceDisplay = "0.00 USD",
            )
        }
        var edited: Long? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).height(640.dp)) {
                        ManageAccountsContent(
                            state = ManageAccountsUiState(rows = rows, isLoading = false),
                            onArchive = {}, onUnarchive = {}, onDelete = {}, onEdit = { edited = it },
                            onAddAccount = {}, onDone = {}, onRetry = {},
                        )
                    }
                }
            }
        }

        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(rows.lastIndex)
        // Bringing the item into view can stop before the trailing padding reaches the viewport.
        repeat(2) { list.performTouchInput { swipeUp() } }
        val menu = compose.onNodeWithContentDescription("Actions for Final account")
        menu.assertIsDisplayed()
        menu.performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.runOnIdle { assertEquals(12L, edited) }
    }

    @Test fun archivedOnlyWalletExplainsEmptyActiveGroupAndOffersAdd() {
        val account = Account(1, "Old account", "bank", "USD", Money.zero("USD"), archived = true)
        var addCount = 0
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                ManageAccountsContent(
                    state = ManageAccountsUiState(
                        rows = listOf(ManageAccountRow(account, false, Money.zero("USD"), "0.00 USD")),
                        isLoading = false,
                    ),
                    onArchive = {}, onUnarchive = {}, onDelete = {}, onEdit = {},
                    onAddAccount = { addCount++ }, onDone = {}, onRetry = {},
                )
            }
        }

        compose.onNodeWithText("No active accounts").assertIsDisplayed()
        compose.onAllNodesWithText("Archived").assertCountEquals(2)
        compose.onNodeWithText("Add account").performClick()
        compose.runOnIdle { assertEquals(1, addCount) }
    }
}
