package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TransactionsFinalRowLargeTextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun lastAmountAndEditActionClearAddButtonAtTwoHundredPercentInRtl() {
        val day = LocalDate.of(2026, 8, 26)
        var edited: Long? = null
        val rows = (1L..12L).map { id ->
            TransactionRow(
                id = id,
                date = day,
                type = TransactionRowType.EXPENSE,
                accountName = "Long savings account name",
                destAccountName = null,
                categoryLabel = CategoryLabel.Named(if (id == 12L) "Final groceries" else "Groceries $id"),
                amount = Money.of(if (id == 12L) "123456.78" else id.toString(), "KWD"),
                destAmount = null,
                amountDisplay = if (id == 12L) "123,456.78 KWD" else "$id.00 KWD",
                destAmountDisplay = null,
                note = null,
            )
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).height(640.dp).testTag("viewport")) {
                        TransactionsListContent(
                            state = TransactionsUiState(
                                sections = listOf(TransactionSection(day, DayRelation.EARLIER, rows)),
                                isLoading = false,
                                window = TrackingWindow.Period(TrackingPeriod.MONTH, day),
                            ),
                            windowActions = DateWindowActions.None,
                            onAddTransaction = {},
                            onEditTransaction = { edited = it },
                            onShowSavedEntry = {},
                            onSavedEntryMessageDone = {},
                            onRetry = {},
                        )
                    }
                }
            }
        }

        // The header occupies index 0; scroll the final row as far as the list allows.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(rows.size)
        val amount = compose.onNodeWithText("123,456.78 KWD", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithText("Add transaction").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("The last amount must stay in the viewport", amount.left >= viewport.left && amount.right <= viewport.right)
        assertTrue(
            "The last amount must clear Add transaction",
            amount.bottom <= add.top || amount.top >= add.bottom || amount.right <= add.left || amount.left >= add.right,
        )
        compose.onNodeWithText("Final groceries").performClick()
        assertEquals(12L, edited)
    }
}
