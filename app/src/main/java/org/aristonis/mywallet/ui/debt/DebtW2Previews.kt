package org.aristonis.mywallet.ui.debt

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtAmounts
import org.aristonis.mywallet.domain.model.DebtDetail
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DebtState
import org.aristonis.mywallet.domain.model.DebtSummary
import org.aristonis.mywallet.domain.model.DebtTotals
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.time.LocalDate

private val previewDay = LocalDate.of(2026, 10, 13)
private val previewDebt = Debt(1, "Alex", DebtSide.I_OWE, "USD", previewDay.minusDays(1))
private val previewSummary = DebtSummary(previewDebt, Money.of("350", "USD"), DebtState.OVERDUE)

@Preview(name = "Debts light", showBackground = true, widthDp = 360)
@Preview(name = "Debts dark", showBackground = true, widthDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Debts large text", showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun DebtsW2Preview() {
    MyWalletTheme(dynamicColor = false) {
        DebtsContent(DebtsUiState(open = listOf(DebtRow(previewSummary, "350.00 USD")),
            totals = DebtTotals.Amounts(Money.of("350", "USD"), Money.zero("USD")),
            iOweDisplay = "350.00 USD", isLoading = false),
            onDone = {}, onOpenDebt = {}, onManageRates = {}, onSelectSide = {}, onRetry = {}, onAddDebt = {})
    }
}

@Preview(name = "Debt detail light", showBackground = true, widthDp = 360)
@Preview(name = "Debt detail dark", showBackground = true, widthDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DebtDetailW2Preview() {
    MyWalletTheme(dynamicColor = false) {
        DebtDetailContent(DebtDetailUiState(detail = DebtDetail(previewSummary, emptyList(),
            DebtAmounts(Money.of("500", "USD"), Money.of("100", "USD"), Money.of("50", "USD"))),
            counterpartyInput = "Alex", dueDate = previewDebt.dueDate,
            originalDisplay = "500.00 USD", repaidDisplay = "100.00 USD", writtenOffDisplay = "50.00 USD",
            remainingDisplay = "350.00 USD",
            isLoading = false), onDone = {}, onEditEntry = {}, onCounterpartyChange = {}, onRename = {},
            onDueDateChange = {}, onNoteChange = {}, onSaveDetails = {}, onRetry = {}, today = { previewDay },
            onWriteOff = {}, onDeleteDebt = {}, onRecordRepayment = {}, onEditRepayment = { _, _ -> },
            snackbar = SnackbarHostState())
    }
}

@Preview(name = "Repayment light", showBackground = true, widthDp = 360)
@Preview(name = "Repayment dark", showBackground = true, widthDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RepaymentW2Preview() {
    MyWalletTheme(dynamicColor = false) {
        RecordRepaymentContent(RecordRepaymentUiState(debt = DebtDetail(previewSummary, emptyList(),
            DebtAmounts(Money.of("350", "USD"), Money.zero("USD"), Money.zero("USD"))),
            date = previewDay, remainingDisplay = "350.00 USD", isLoading = false),
            onDone = {}, onOpenEntry = {}, onAccountSelected = {}, onPrincipalChanged = {},
            onAccountPrincipalChanged = {}, onInterestChanged = {}, onInterestCategorySelected = {},
            onPayRest = {}, onRateChanged = { _, _ -> }, onDateSelected = {}, onNoteChanged = {},
            onSubmit = {})
    }
}
