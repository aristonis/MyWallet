package org.aristonis.mywallet.ui.loading

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.category.ManageCategoriesContent
import org.aristonis.mywallet.ui.category.ManageCategoriesUiState
import org.aristonis.mywallet.ui.debt.DebtDetailContent
import org.aristonis.mywallet.ui.debt.DebtDetailUiState
import org.aristonis.mywallet.ui.rates.ManageRatesContent
import org.aristonis.mywallet.ui.rates.ManageRatesUiState
import org.aristonis.mywallet.ui.transaction.TransactionDetailContent
import org.aristonis.mywallet.ui.transaction.TransactionDetailUiState
import org.aristonis.mywallet.ui.debt.DebtsContent
import org.aristonis.mywallet.ui.debt.DebtsUiState
import org.aristonis.mywallet.ui.reports.ReportsContent
import org.aristonis.mywallet.ui.reports.ReportsExportState
import org.aristonis.mywallet.ui.reports.ReportsUiState
import org.aristonis.mywallet.ui.reports.charts.ChartTags
import org.aristonis.mywallet.ui.reports.charts.NetWorthContent
import org.aristonis.mywallet.ui.reports.charts.NetWorthUiState
import org.aristonis.mywallet.ui.reports.charts.SpendingBreakdownContent
import org.aristonis.mywallet.ui.reports.charts.SpendingUiState
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.transaction.TransactionsListContent
import org.aristonis.mywallet.ui.transaction.TransactionsUiState
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ListReportBrandLoadingTest {
    @get:Rule val compose = createComposeRule()
    private val window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 10, 14))
    private val loading = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                Box(Modifier.width(360.dp).height(640.dp)) { content() }
            }
        }
    }

    private fun assertBoundedBodyLoader() {
        val bounds = compose.onNode(loading).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("Loader fills body width", bounds.width / density > 280f)
        assertTrue("Loader fills remaining body height", bounds.height / density > 200f)
        assertTrue("Loader stays below app bar", bounds.top / density >= 48f)
        assertTrue("Loader stays within body", bounds.bottom / density <= 641f)
    }

    @Test fun transactionDetail_initialLoadingFillsBody_andKeepsLoadingTag() {
        show { TransactionDetailContent(TransactionDetailUiState(), {}, {}, {}, {}, {}, {}) }
        assertBoundedBodyLoader()
        compose.onNodeWithTag("transaction-detail-loading").assertIsDisplayed()
    }

    @Test fun debtDetail_initialLoadingFillsBody() {
        show {
            DebtDetailContent(
                state = DebtDetailUiState(), onDone = {}, onEditEntry = {},
                onCounterpartyChange = {}, onRename = {}, onDueDateChange = {},
                onNoteChange = {}, onSaveDetails = {}, onRetry = {},
                today = { LocalDate.of(2026, 10, 14) }, onWriteOff = {}, onDeleteDebt = {},
                onRecordRepayment = {}, onEditRepayment = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
            )
        }
        assertBoundedBodyLoader()
    }

    @Test fun categories_initialLoadingFillsBody() {
        show {
            ManageCategoriesContent(ManageCategoriesUiState(), { _, _, _ -> }, { _, _ -> },
                {}, {}, { 0 }, {}, {})
        }
        assertBoundedBodyLoader()
    }

    @Test fun rates_initialLoadingFillsBody() {
        show { ManageRatesContent(ManageRatesUiState(), { _, _ -> }, {}, {}, {}) }
        assertBoundedBodyLoader()
    }

    @Test fun transactions_initialLoadingFillsBodyBelowFilters() {
        show {
            TransactionsListContent(TransactionsUiState(), DateWindowActions.None,
                onAddTransaction = {}, onOpenTransaction = {}, onShowSavedEntry = {},
                onSavedEntryMessageDone = {}, onRetry = {})
        }
        assertBoundedBodyLoader()
    }

    @Test fun debts_initialLoadingFillsBody_andCompletionRemovesItImmediately() {
        val state = mutableStateOf(DebtsUiState())
        show { DebtsContent(state.value, {}, {}, {}, {}, {}, {}) }
        assertBoundedBodyLoader()
        compose.runOnIdle { state.value = DebtsUiState(isLoading = false) }
        compose.onNode(loading).assertDoesNotExist()
    }

    @Test fun reports_initialLoadingFillsBody_andFailureOffersRetry() {
        val state = mutableStateOf(ReportsUiState(window))
        show { ReportsContent(state.value, DateWindowActions.None, {}, {}, ReportsExportState.Idle, {}, {}) }
        assertBoundedBodyLoader()
        compose.runOnIdle { state.value = ReportsUiState(window, loadFailed = true) }
        compose.onNode(loading).assertDoesNotExist()
        val retry = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_retry)
        compose.onNodeWithText(retry).assertIsDisplayed()
    }

    @Test fun netWorth_initialLoadingFillsBody_andKeepsRestatementVisible() {
        show { NetWorthContent(NetWorthUiState(window), DateWindowActions.None, {}, {}, {}) }
        assertBoundedBodyLoader()
        compose.onNodeWithTag(ChartTags.RESTATEMENT_LABEL).assertIsDisplayed()
    }

    @Test fun spending_initialLoadingFillsBodyBelowDateWindow() {
        show { SpendingBreakdownContent(SpendingUiState(window), DateWindowActions.None, {}, {}, {}) }
        assertBoundedBodyLoader()
    }
}
