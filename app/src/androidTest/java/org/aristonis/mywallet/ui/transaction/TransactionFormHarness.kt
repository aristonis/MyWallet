package org.aristonis.mywallet.ui.transaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

internal class TransactionFormHarness(type: TransactionType) {
    var state by mutableStateOf(AddTransactionUiState(
        type = type, date = LocalDate.of(2026, 10, 10),
        accounts = listOf(account(1, "Cash", "USD"), account(2, "Bank", "EUR")),
        selectedAccountId = 1, currencyCode = "USD", counterparty = "Ali", note = "Receipt",
        allCategories = listOf(Category(5, "Salary", CategoryKind.INCOME), Category(6, "Food", CategoryKind.EXPENSE)),
        selectedCategoryId = if (type == TransactionType.EARNED_UNPAID) 5 else 6,
        fx = FxSnapshot("SYP", mapOf("USD" to BigDecimal("13000"), "EUR" to BigDecimal("14000")),
            listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("SYP", "£S", 2)))))
        private set
    var submitted: AddTransactionUiState? = null
        private set
    private val quoter = RateQuoter(MoneyParser(Locale.US), MoneyFormatter(Locale.US))

    fun change(update: (AddTransactionUiState) -> AddTransactionUiState) {
        val next = update(state)
        val quote = quoter.quote(next.quoteInput())
        state = next.copy(rateFields = quote.rateFields, baseEquivalent = quote.baseEquivalent,
            pairRate = quote.pairRate, receivedDisplay = quote.receivedDisplay, rateRequired = quote.rateRequired)
    }

    @Composable
    fun Content() {
        AddTransactionContent(state,
            onFeeValueChanged = {}, onFeeModeChanged = {}, onFeeCategorySelected = {}, onRetryFeeCategory = {},
            onTypeSelected = { type -> change { it.copy(type = type) } },
            onAccountSelected = { id -> change { it.copy(selectedAccountId = id) } },
            onCategorySelected = { id -> change { it.copy(selectedCategoryId = id) } },
            onSubCategorySelected = { id -> change { it.copy(selectedSubCategoryId = id) } },
            onDestAccountSelected = { id -> change { it.copy(destAccountId = id) } },
            onAmountChanged = { amount -> change { it.copy(amountInput = amount) } },
            onRateChanged = { code, rate -> change { it.copy(rateInputs = it.rateInputs + (code to rate)) } },
            onDateSelected = { date -> change { it.copy(date = date) } },
            onNoteChanged = { note -> change { it.copy(note = note) } },
            onSubmit = { submitted = state }, onCancel = {},
            onCounterpartyChanged = { name -> change { it.copy(counterparty = name,
                counterpartySuggestions = if (name == "Sa") listOf("Sara") else emptyList()) } },
            onSuggestionSelected = { name -> change { it.copy(counterparty = name, counterpartySuggestions = emptyList()) } },
            onCurrencySelected = { code -> change { it.copy(currencyCode = code) } },
            onDebtSideSelected = { side -> change { it.copy(debtSide = side) } })
    }

    private fun account(id: Long, name: String, code: String) = Account(id, name, "cash", code, Money.zero(code))
}
