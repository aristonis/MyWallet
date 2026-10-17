package org.aristonis.mywallet.ui.loader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.AppRootContent
import org.aristonis.mywallet.ui.StartDestination
import org.aristonis.mywallet.ui.account.AccountDetailContent
import org.aristonis.mywallet.ui.account.AccountDetailUiState
import org.aristonis.mywallet.ui.account.ManageAccountsContent
import org.aristonis.mywallet.ui.account.ManageAccountsUiState
import org.aristonis.mywallet.ui.debt.RecordRepaymentContent
import org.aristonis.mywallet.ui.debt.RecordRepaymentUiState
import org.aristonis.mywallet.ui.deduction.DeductionEditorContent
import org.aristonis.mywallet.ui.deduction.DeductionEditorUiState
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormBrandLoadingIntegrationTest {
    @get:Rule val compose = createComposeRule()

    private val loading = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
    ) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                Box(Modifier.size(320.dp, 600.dp)) { content() }
            }
        }
    }

    private fun assertBodyLoader() {
        compose.onNode(loading).assertIsDisplayed()
            .assertWidthIsAtLeast(240.dp).assertHeightIsAtLeast(300.dp)
    }

    @Test fun startupLoadingHasAnAccessibleBoundedBrandSurfaceImmediately() {
        show { AppRootContent(StartDestination.LOADING) }

        assertBodyLoader()
    }

    @Test fun accountManagementLoadingFillsTheBodyBelowItsControls() {
        show {
            ManageAccountsContent(ManageAccountsUiState(), onArchive = {}, onUnarchive = {},
                onDelete = {}, onEdit = {}, onAddAccount = {}, onDone = {}, onRetry = {})
        }

        assertBodyLoader()
    }

    @Test fun accountDetailLoadingKeepsItsExistingLoadingTagOnTheBodySurface() {
        show {
            AccountDetailContent(AccountDetailUiState(), onBack = {}, onRetry = {},
                onAddTransaction = {}, onPayCard = {}, onEditAccount = {}, onOpenEntry = {},
                onLoadMore = {}, onManageRates = {})
        }

        assertBodyLoader()
        compose.onNodeWithTag("account-detail-loading").assertIsDisplayed()
    }

    @Test fun deductionEditorLoadingFillsTheBodyAndDisappearsWithoutAnimationTime() {
        val state = mutableStateOf(DeductionEditorUiState())
        compose.mainClock.autoAdvance = false
        show {
            DeductionEditorContent(state.value, onNameChange = {}, onValueChange = {},
                onModeSelect = {}, onCurrencySelect = {}, onTimingSelect = {},
                onExpenseCategorySelect = {}, onExpenseSubCategorySelect = {}, onAutoAddToggle = {},
                onEnabledChange = {}, onSubmit = {}, onRetry = {})
        }
        compose.mainClock.advanceTimeByFrame()
        assertBodyLoader()

        compose.runOnIdle { state.value = state.value.copy(isLoading = false, notFound = true) }
        compose.mainClock.advanceTimeByFrame()

        compose.onNode(loading).assertDoesNotExist()
    }

    @Test fun repaymentLoadingFillsTheBodyBelowTheAppBar() {
        show {
            RecordRepaymentContent(RecordRepaymentUiState(date = LocalDate.of(2026, 10, 17)), onDone = {}, onOpenEntry = {},
                onAccountSelected = {}, onPrincipalChanged = {}, onAccountPrincipalChanged = {},
                onInterestChanged = {}, onInterestCategorySelected = {}, onPayRest = {},
                onRateChanged = { _, _ -> }, onDateSelected = {}, onNoteChanged = {}, onSubmit = {})
        }

        assertBodyLoader()
    }
}
