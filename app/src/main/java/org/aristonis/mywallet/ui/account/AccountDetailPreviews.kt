package org.aristonis.mywallet.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.transaction.TransactionRow
import org.aristonis.mywallet.ui.transaction.TransactionRowType
import java.time.LocalDate

private val previewDay = LocalDate.of(2026, 10, 13)

private val previewAccount = AccountDetailHeader(
    id = 1, name = "Everyday wallet", typeKey = "cash", currencyCode = "USD",
    archived = false, nativeDisplay = "840.00 USD",
)

private val previewCard = AccountDetailHeader(
    id = 2, name = "Everyday card", typeKey = "credit_card", currencyCode = "USD",
    archived = false, nativeDisplay = "-120.00 USD",
    credit = CreditDetail(owedDisplay = "120.00 USD", limitDisplay = "2,000.00 USD", availableDisplay = "1,880.00 USD"),
)

private val previewEntries = listOf(
    TransactionRow(
        id = 2, date = previewDay, type = TransactionRowType.EXPENSE,
        accountName = "Everyday wallet", destAccountName = null, categoryLabel = CategoryLabel.Named("Groceries"),
        amount = Money.of("42.80", "USD"), destAmount = null,
        amountDisplay = "42.80 USD", destAmountDisplay = null, note = null,
    ),
    TransactionRow(
        id = 1, date = previewDay.minusDays(1), type = TransactionRowType.INCOME,
        accountName = "Everyday wallet", destAccountName = null, categoryLabel = CategoryLabel.Named("Salary"),
        amount = Money.of("1500", "USD"), destAmount = null,
        amountDisplay = "1,500.00 USD", destAmountDisplay = null, note = null,
    ),
)

private class AccountDetailPreviewSamples : PreviewParameterProvider<AccountDetailUiState> {
    override val values: Sequence<AccountDetailUiState> = sequenceOf(
        AccountDetailUiState(isLoading = false, header = previewAccount, entries = previewEntries, hasMore = true),
        // A long balance, so the hero's step-down shows at large text.
        AccountDetailUiState(
            isLoading = false,
            header = previewAccount.copy(nativeDisplay = "123,456,789,012.00 USD"),
            entries = previewEntries,
        ),
        AccountDetailUiState(isLoading = false, header = previewCard, entries = previewEntries.take(1)),
        AccountDetailUiState(
            isLoading = false,
            header = previewCard.copy(
                nativeDisplay = "-2,120.00 USD",
                credit = CreditDetail(
                    owedDisplay = "2,120.00 USD", limitDisplay = "2,000.00 USD",
                    availableDisplay = "0.00 USD", overLimit = true,
                ),
            ),
            entries = previewEntries.take(1),
        ),
        AccountDetailUiState(),
        AccountDetailUiState(isLoading = false, notFound = true),
        AccountDetailUiState(isLoading = false, loadFailure = UiMessage.DataUnreadable),
        AccountDetailUiState(isLoading = false, header = previewAccount),
        AccountDetailUiState(
            isLoading = false,
            header = previewAccount.copy(currencyCode = "EUR", nativeDisplay = "35.00 EUR", missingRate = true),
        ),
    )
}

@Preview(name = "Account detail light", showBackground = true, widthDp = 360)
@Preview(name = "Account detail dark", showBackground = true, widthDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Account detail large text", showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun AccountDetailPreview(
    @PreviewParameter(AccountDetailPreviewSamples::class) state: AccountDetailUiState,
) {
    MyWalletTheme(dynamicColor = false) {
        AccountDetailContent(
            state = state, onBack = {}, onRetry = {}, onAddTransaction = {}, onPayCard = {},
            onEditAccount = {}, onOpenEntry = {}, onLoadMore = {}, onManageRates = {},
        )
    }
}
