package org.aristonis.mywallet.ui.transaction

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.UiMessage
import java.math.BigDecimal
import java.time.LocalDate

private val previewDate = LocalDate.of(2026, 10, 13)
private val previewCash = Account(
    id = 1,
    name = "Everyday wallet",
    typeKey = "cash",
    currencyCode = "USD",
    openingBalance = Money.zero("USD"),
)
private val previewBank = Account(
    id = 2,
    name = "Bank account",
    typeKey = "bank",
    currencyCode = "USD",
    openingBalance = Money.zero("USD"),
)
private val previewFood = Category(id = 3, name = "Food", kind = CategoryKind.EXPENSE)

internal class AddTransactionPreviewStates : PreviewParameterProvider<AddTransactionUiState> {
    override val values: Sequence<AddTransactionUiState> = sequenceOf(
        AddTransactionUiState(
            accounts = listOf(previewCash, previewBank),
            allCategories = listOf(previewFood),
            selectedAccountId = previewCash.id,
            selectedCategoryId = previewFood.id,
            amountInput = "42.80",
            note = "Groceries",
            date = previewDate,
        ),
        AddTransactionUiState(
            accounts = listOf(previewCash),
            selectedAccountId = previewCash.id,
            error = UiMessage.AmountMissing,
            date = previewDate,
        ),
        AddTransactionUiState(
            type = TransactionType.TRANSFER,
            accounts = listOf(previewCash, previewBank),
            selectedAccountId = previewCash.id,
            destAccountId = previewBank.id,
            amountInput = "75.00",
            date = previewDate,
        ),
    )
}

internal class EditTransactionPreviewStates : PreviewParameterProvider<EditTransactionUiState> {
    private val expense = Transaction.Expense(
        id = 4,
        accountId = previewCash.id,
        amount = Money.of("42.80", "USD"),
        categoryId = previewFood.id,
        date = previewDate,
        note = "Groceries",
    )

    override val values: Sequence<EditTransactionUiState> = sequenceOf(
        EditTransactionUiState(
            loaded = expense,
            type = TransactionType.EXPENSE,
            accounts = listOf(previewCash),
            allCategories = listOf(previewFood),
            selectedAccountId = previewCash.id,
            selectedCategoryId = previewFood.id,
            amountInput = "42.80",
            date = previewDate,
            note = "Groceries",
        ),
        EditTransactionUiState(
            loaded = expense,
            type = TransactionType.EXPENSE,
            accounts = listOf(previewCash),
            selectedAccountId = previewCash.id,
            amountInput = "",
            date = previewDate,
            error = UiMessage.AmountMissing,
        ),
        EditTransactionUiState(
            loaded = Transaction.Transfer(
                id = 5,
                sourceAccountId = previewCash.id,
                destAccountId = previewBank.id,
                sourceAmount = Money.of("75.00", "USD"),
                destAmount = Money.of("75.00", "USD"),
                rateUsed = BigDecimal.ONE,
                date = previewDate,
            ),
            type = TransactionType.TRANSFER,
            accounts = listOf(previewCash, previewBank),
            selectedAccountId = previewCash.id,
            destAccountId = previewBank.id,
            amountInput = "75.00",
            date = previewDate,
        ),
    )
}
