package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.usecase.RecordExpense
import org.aristonis.mywallet.domain.usecase.RecordIncome
import org.aristonis.mywallet.domain.usecase.RecordTransfer
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

/** Which of the three ledger actions the form is recording. */
enum class TransactionType { INCOME, EXPENSE, TRANSFER }

/** Immutable snapshot the add-transaction screen renders from. */
data class AddTransactionUiState(
    val type: TransactionType = TransactionType.EXPENSE,
    val accounts: List<Account> = emptyList(),
    val allCategories: List<Category> = emptyList(),
    val selectedAccountId: Long? = null,
    val selectedCategoryId: Long? = null,
    val destAccountId: Long? = null,
    val amountInput: String = "",
    val date: LocalDate,
    val note: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
) {
    /** Categories offered for the current type: income lists income, expense lists expense, transfer none. */
    val categoriesForType: List<Category>
        get() = when (type) {
            TransactionType.INCOME -> allCategories.filter { it.kind == CategoryKind.INCOME && !it.isSubCategory }
            TransactionType.EXPENSE -> allCategories.filter { it.kind == CategoryKind.EXPENSE && !it.isSubCategory }
            TransactionType.TRANSFER -> emptyList()
        }

    /** Destination candidates for a transfer: every account except the chosen source. */
    val destAccounts: List<Account>
        get() = accounts.filter { it.id != selectedAccountId }

    val canSubmit: Boolean
        get() = when (type) {
            TransactionType.TRANSFER ->
                selectedAccountId != null && destAccountId != null &&
                    destAccountId != selectedAccountId && amountInput.isNotBlank() && !isSubmitting
            else ->
                selectedAccountId != null && selectedCategoryId != null &&
                    amountInput.isNotBlank() && !isSubmitting
        }
}

/**
 * Records an income, expense, or transfer. One view model, a [TransactionType] selector, and a
 * `when` that routes [submit] to the matching use-case. The amount is always built in the SELECTED
 * account's currency, so it matches by construction (the domain guard stays as defense). Because the
 * write goes through the same repositories Home reads, recording a transaction moves the balances and
 * net worth automatically — this view model never touches balance math.
 */
@HiltViewModel
class AddTransactionViewModel @Inject constructor(
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val recordIncome: RecordIncome,
    private val recordExpense: RecordExpense,
    private val recordTransfer: RecordTransfer,
    private val today: TodayProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(AddTransactionUiState(date = today.today()))
    val state: StateFlow<AddTransactionUiState> = _state.asStateFlow()

    init {
        combine(accounts.observeAll(), categories.observeAll()) { accountList, categoryList ->
            accountList to categoryList
        }
            .onEach { (accountList, categoryList) ->
                _state.update { current ->
                    current.copy(
                        accounts = accountList,
                        allCategories = categoryList,
                        // Pre-select a source account for convenience; never auto-fill the destination.
                        selectedAccountId = current.selectedAccountId ?: accountList.firstOrNull()?.id,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun selectType(type: TransactionType) = _state.update {
        // Switching type clears the category: the use-cases only check a category EXISTS, not that its
        // kind matches, so a stale income category must not survive into an expense (and vice versa).
        it.copy(type = type, selectedCategoryId = null, error = null)
    }

    fun selectAccount(id: Long) = _state.update { it.copy(selectedAccountId = id) }

    fun selectCategory(id: Long) = _state.update { it.copy(selectedCategoryId = id) }

    fun selectDestAccount(id: Long) = _state.update { it.copy(destAccountId = id) }

    fun setAmount(text: String) = _state.update { it.copy(amountInput = text) }

    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }

    fun setNote(text: String) = _state.update { it.copy(note = text) }

    /**
     * Clear the one-shot `saved` signal once the screen has acted on it, and reset the form for the
     * next entry. Without a nav back-stack this view model is retained across the Home toggle, so a
     * stale `saved == true` would otherwise bounce a re-opened screen straight back to Home — and a
     * stale `date` would silently post the next transaction to the wrong day. The date re-reads
     * "today"; the type and source account are kept for fast repeat entry.
     */
    fun acknowledgeSaved() = _state.update {
        it.copy(
            saved = false,
            amountInput = "",
            note = "",
            selectedCategoryId = null,
            destAccountId = null,
            date = today.today(),
            error = null,
        )
    }

    fun submit() {
        val snapshot = _state.value
        val account = snapshot.accounts.firstOrNull { it.id == snapshot.selectedAccountId } ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            try {
                record(snapshot, account)
                _state.update { it.copy(isSubmitting = false, saved = true) }
            } catch (e: WalletException) {
                _state.update { it.copy(isSubmitting = false, error = e.message) }
            } catch (e: IllegalArgumentException) {
                // Bad amount (parse) or a model invariant (amount > 0, no self-transfer) — surface it.
                _state.update { it.copy(isSubmitting = false, error = e.message) }
            }
        }
    }

    private suspend fun record(snapshot: AddTransactionUiState, account: Account) {
        val amount = parseAmount(snapshot.amountInput, account.currencyCode)
        val note = snapshot.note.trim().ifEmpty { null }
        when (snapshot.type) {
            TransactionType.INCOME -> recordIncome(
                accountId = account.id,
                amount = amount,
                categoryId = requireCategory(snapshot),
                date = snapshot.date,
                note = note,
            )
            TransactionType.EXPENSE -> recordExpense(
                accountId = account.id,
                amount = amount,
                categoryId = requireCategory(snapshot),
                date = snapshot.date,
                note = note,
            )
            TransactionType.TRANSFER -> recordTransfer(
                sourceAccountId = account.id,
                destAccountId = requireDestAccount(snapshot),
                amount = amount,
                date = snapshot.date,
                note = note,
            )
        }
    }

    private fun requireCategory(snapshot: AddTransactionUiState): Long =
        snapshot.selectedCategoryId ?: throw IllegalArgumentException("Choose a category")

    private fun requireDestAccount(snapshot: AddTransactionUiState): Long =
        snapshot.destAccountId ?: throw IllegalArgumentException("Choose a destination account")

    /**
     * Parse the typed amount, failing loud with a user-facing message. Rejects blank, non-numeric,
     * non-positive, and an absurd exponent (which would otherwise blow up toPlainString to an OOM).
     * Money is built in [currencyCode] so it never touches a float.
     */
    private fun parseAmount(input: String, currencyCode: String): Money {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Enter an amount" }
        val parsed = try {
            BigDecimal(trimmed)
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Enter a valid number")
        }
        require(parsed.scale() in -MAX_AMOUNT_SCALE..MAX_AMOUNT_SCALE) { "Enter a realistic amount" }
        require(parsed.signum() > 0) { "Amount must be greater than 0" }
        return Money.of(parsed, currencyCode)
    }

    private companion object {
        // A real amount sits far inside this scale; an extreme exponent is a paste/typo that would also
        // OOM toPlainString(), so it is rejected at the boundary rather than crash later (mirrors rates).
        private const val MAX_AMOUNT_SCALE = 30
    }
}
