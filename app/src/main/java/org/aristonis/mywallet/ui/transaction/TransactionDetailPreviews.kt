package org.aristonis.mywallet.ui.transaction

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.time.LocalDate

/** Each state at phone width in light, dark and doubled text, the cases most likely to break layout. */
@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(name = "Dark", showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large text", showBackground = true, widthDp = 360, fontScale = 2f)
private annotation class DetailPreviews

private val previewDay = LocalDate.of(2026, 10, 13)

private val previewExpense = TransactionRow(
    id = 1, date = previewDay, type = TransactionRowType.EXPENSE,
    accountName = "Everyday wallet", destAccountName = null,
    categoryLabel = CategoryLabel.Named("Groceries"),
    amount = Money.of("42.80", "USD"), destAmount = null,
    amountDisplay = "42.80 USD", destAmountDisplay = null, note = "Weekly shop",
    feeDisplay = "0.50 USD",
)

private val previewIncome = TransactionRow(
    id = 2, date = previewDay, type = TransactionRowType.INCOME,
    accountName = "Main bank", destAccountName = null,
    categoryLabel = CategoryLabel.Named("Salary"),
    amount = Money.of("1000", "USD"), destAmount = null,
    amountDisplay = "1,000.00 USD", destAmountDisplay = null, note = null,
    grossAmount = Money.of("1000", "USD"), grossAmountDisplay = "1,000.00 USD",
    accountMovement = Money.of("950", "USD"), accountMovementDisplay = "950.00 USD",
    deductions = listOf(
        TransactionDeductionRow("Income tax", DeductionTiming.NOW, Money.of("50", "USD"), "50.00 USD", null),
        TransactionDeductionRow("Pension fund", DeductionTiming.LATER, Money.of("40", "USD"), "40.00 USD", null),
    ),
)

private val previewTransfer = TransactionRow(
    id = 3, date = previewDay, type = TransactionRowType.TRANSFER,
    accountName = "Dollar account", destAccountName = "Euro travel account",
    categoryLabel = null,
    amount = Money.of("100", "USD"), destAmount = Money.of("90", "EUR"),
    amountDisplay = "100.00 USD", destAmountDisplay = "90.00 EUR", note = null,
)

@Composable
private fun PreviewContent(state: TransactionDetailUiState) {
    MyWalletTheme(dynamicColor = false) {
        TransactionDetailContent(
            state = state, onBack = {}, onRetry = {}, onEdit = {}, onDelete = {}, onUndoDelete = {},
            onConfirmDelete = {},
        )
    }
}

@DetailPreviews
@Composable
private fun ExpenseDetailPreview() = PreviewContent(TransactionDetailUiState(isLoading = false, row = previewExpense))

@DetailPreviews
@Composable
private fun IncomeWithDeductionsDetailPreview() =
    PreviewContent(TransactionDetailUiState(isLoading = false, row = previewIncome))

@DetailPreviews
@Composable
private fun TransferDetailPreview() = PreviewContent(TransactionDetailUiState(isLoading = false, row = previewTransfer))

@DetailPreviews
@Composable
private fun LoadingDetailPreview() = PreviewContent(TransactionDetailUiState())

@DetailPreviews
@Composable
private fun NotFoundDetailPreview() = PreviewContent(TransactionDetailUiState(isLoading = false, notFound = true))

@DetailPreviews
@Composable
private fun ReadFailureDetailPreview() =
    PreviewContent(TransactionDetailUiState(isLoading = false, loadFailure = UiMessage.DataUnreadable))

@DetailPreviews
@Composable
private fun DeleteConfirmationPreview() {
    MyWalletTheme(dynamicColor = false) {
        TransactionDeleteDialog(row = previewExpense, onConfirm = {}, onDismiss = {})
    }
}
