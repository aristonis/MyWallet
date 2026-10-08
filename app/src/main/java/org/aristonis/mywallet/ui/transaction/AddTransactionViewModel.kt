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
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import org.aristonis.mywallet.ui.message.MissingSelectionException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.RecordExpense
import org.aristonis.mywallet.domain.usecase.RecordIncome
import org.aristonis.mywallet.domain.usecase.RecordTransfer
import org.aristonis.mywallet.domain.usecase.RecordWithTypedRates
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
    val selectedSubCategoryId: Long? = null,
    val destAccountId: Long? = null,
    val amountInput: String = "",
    val date: LocalDate,
    val note: String = "",
    val isSubmitting: Boolean = false,
    val error: UiMessage? = null,
    val saved: Boolean = false,
    /** Rates typed on the form, by currency code; kept across type and account changes. */
    val rateInputs: Map<String, String> = emptyMap(),
    /** The saved rates and base currency the previews are priced against; null until loaded. */
    val fx: FxSnapshot? = null,
    val saveTransactionRates: Boolean = true,
    val rateFields: List<RateField> = emptyList(),
    /** The amount in the base currency, e.g. "8.00 SYP"; null when there is nothing to convert. */
    val baseEquivalent: String? = null,
    /** For a cross-currency transfer, what 1 unit of the source buys in the destination currency. */
    val pairRate: PairRate? = null,
    /** For a cross-currency transfer, what lands in the destination account. */
    val receivedDisplay: String? = null,
    /** A transfer cannot be priced until every currency in it has a rate, typed or saved. */
    val rateRequired: Boolean = false,
) {
    /**
     * The sub-categories offered under the chosen category. Filtered by kind as well as parent:
     * restored data can hold a child whose kind differs from its parent's, and offering one would
     * put a choice on screen that the domain then refuses, leaving Save dead with no explanation.
     */
    val subCategoriesForSelected: List<Category>
        get() = selectedCategoryId?.let { parent ->
            val parentKind = allCategories.firstOrNull { it.id == parent }?.kind
            allCategories.filter { it.parentId == parent && it.kind == parentKind }
        }.orEmpty()

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
                    destAccountId != selectedAccountId && amountInput.isNotBlank() && !isSubmitting &&
                    !rateRequired
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
 *
 * Rates: a foreign-currency entry shows its rate on the form and what the amount is worth in the base
 * currency, so a missing or stale rate is fixed here instead of in a detour through Settings. Whether a
 * typed rate also becomes the saved rate is the user's setting; with it off, a typed rate prices only
 * the transfer being recorded.
 */
@HiltViewModel
class AddTransactionViewModel @Inject constructor(
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val recordIncome: RecordIncome,
    private val recordExpense: RecordExpense,
    private val recordTransfer: RecordTransfer,
    private val today: TodayProvider,
    private val moneyParser: MoneyParser,
    fx: FxRepository,
    settings: SettingsRepository,
    moneyFormatter: MoneyFormatter,
    private val recordWithTypedRates: RecordWithTypedRates,
) : ViewModel() {

    private val quoter = RateQuoter(moneyParser, moneyFormatter)
    private val _state = MutableStateFlow(AddTransactionUiState(date = today.today()))
    val state: StateFlow<AddTransactionUiState> = _state.asStateFlow()

    init {
        combine(accounts.observeAll(), categories.observeAll()) { accountList, categoryList ->
            // Archived accounts are not transactable — keep them out of every picker (and the
            // pre-select), or money recorded against them would silently vanish from net worth.
            accountList.filterNot { it.archived } to categoryList
        }
            .onEach { (accountList, categoryList) ->
                mutate { current ->
                    current.copy(
                        accounts = accountList,
                        allCategories = categoryList,
                        // Pre-select a source account for convenience; never auto-fill the destination.
                        selectedAccountId = current.selectedAccountId ?: accountList.firstOrNull()?.id,
                    )
                }
            }
            .launchIn(viewModelScope)
        fx.observeFx().onEach { snapshot -> mutate { it.copy(fx = snapshot) } }.launchIn(viewModelScope)
        settings.observeOrNull()
            .onEach { saved -> mutate { it.copy(saveTransactionRates = saved?.saveTransactionRates ?: true) } }
            .launchIn(viewModelScope)
    }

    /**
     * Every change goes through here so the rate previews are recomputed in the same step. Deriving
     * them later, off another flow, would leave a moment where [submit] reads a fresh amount next to
     * a stale rate field.
     */
    private fun mutate(change: (AddTransactionUiState) -> AddTransactionUiState) = _state.update { current ->
        val next = change(current)
        val quote = quoter.quote(next)
        next.copy(
            rateFields = quote.rateFields,
            baseEquivalent = quote.baseEquivalent,
            pairRate = quote.pairRate,
            receivedDisplay = quote.receivedDisplay,
            rateRequired = quote.rateRequired,
        )
    }

    fun selectType(type: TransactionType) = mutate {
        // Switching type clears the category: the use-cases only check a category EXISTS, not that its
        // kind matches, so a stale income category must not survive into an expense (and vice versa).
        it.copy(type = type, selectedCategoryId = null, selectedSubCategoryId = null, error = null)
    }

    fun selectAccount(id: Long) = mutate { it.copy(selectedAccountId = id) }

    // Changing the category drops the child with it: the previous category's child would otherwise
    // ride along and be refused by the domain guard at save time.
    fun selectCategory(id: Long) = _state.update {
        it.copy(selectedCategoryId = id, selectedSubCategoryId = null)
    }

    fun selectSubCategory(id: Long?) = _state.update { it.copy(selectedSubCategoryId = id) }

    fun selectDestAccount(id: Long) = mutate { it.copy(destAccountId = id) }

    fun setAmount(text: String) = mutate { it.copy(amountInput = text) }

    fun setRate(currencyCode: String, input: String) = mutate {
        it.copy(rateInputs = it.rateInputs + (currencyCode to input))
    }

    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }

    fun setNote(text: String) = _state.update { it.copy(note = text) }

    /**
     * Clear the one-shot `saved` signal once the screen has acted on it, and reset the form for the
     * next entry. Without a nav back-stack this view model is retained across the Home toggle, so a
     * stale `saved == true` would otherwise bounce a re-opened screen straight back to Home — and a
     * stale `date` would silently post the next transaction to the wrong day. The date re-reads
     * "today"; the type and source account are kept for fast repeat entry. Typed rates are dropped:
     * with saving on they are already the saved rates, and with it off they were for that entry only.
     */
    fun acknowledgeSaved() = mutate {
        it.copy(
            saved = false,
            amountInput = "",
            note = "",
            selectedCategoryId = null,
            selectedSubCategoryId = null,
            destAccountId = null,
            date = today.today(),
            error = null,
            rateInputs = emptyMap(),
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
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage()) }
            } catch (e: IllegalArgumentException) {
                // A bad amount or a missing choice names itself; a model invariant (amount > 0, no
                // self-transfer) does not, and its text was written for a stack trace.
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage()) }
            }
        }
    }

    /**
     * Validates everything first, then records. Typed rates that should become the saved rates are
     * written after the record and in the same database transaction, so a refused entry keeps no rate
     * and a failed rate write takes the entry back out; a retry never finds half of it saved.
     */
    private suspend fun record(snapshot: AddTransactionUiState, account: Account) {
        val amount = moneyParser.parseAmount(snapshot.amountInput, account.currencyCode)
        val typedRates = typedRates(snapshot)
        val write = writeFor(snapshot, account, amount, typedRates)
        val toKeep = ratesToKeep(snapshot, typedRates)
        if (toKeep.isEmpty()) write() else recordWithTypedRates(toKeep, write)
    }

    /**
     * The record call for the chosen type. A missing category or destination is thrown here, before
     * anything is written. A transfer is priced at the typed rates whether or not they are kept, so it
     * records exactly what the preview showed.
     */
    private fun writeFor(
        snapshot: AddTransactionUiState,
        account: Account,
        amount: Money,
        typedRates: Map<String, BigDecimal>,
    ): suspend () -> Long {
        val note = snapshot.note.trim().ifEmpty { null }
        return when (snapshot.type) {
            TransactionType.INCOME -> {
                val categoryId = requireCategory(snapshot)
                suspend {
                    recordIncome(
                        accountId = account.id,
                        amount = amount,
                        categoryId = categoryId,
                        subCategoryId = snapshot.selectedSubCategoryId,
                        date = snapshot.date,
                        note = note,
                    )
                }
            }
            TransactionType.EXPENSE -> {
                val categoryId = requireCategory(snapshot)
                suspend {
                    recordExpense(
                        accountId = account.id,
                        amount = amount,
                        categoryId = categoryId,
                        subCategoryId = snapshot.selectedSubCategoryId,
                        date = snapshot.date,
                        note = note,
                    )
                }
            }
            TransactionType.TRANSFER -> {
                val destId = requireDestAccount(snapshot)
                suspend {
                    recordTransfer(
                        sourceAccountId = account.id,
                        destAccountId = destId,
                        amount = amount,
                        date = snapshot.date,
                        note = note,
                        rateOverrides = typedRates,
                    )
                }
            }
        }
    }

    /**
     * The rates the user typed, parsed strictly. Untouched fields are left out: they still hold the
     * saved rate, which needs neither re-saving nor overriding. A read-only field is left out too, so
     * an income or expense with saving off never carries a rate anywhere.
     */
    private fun typedRates(snapshot: AddTransactionUiState): Map<String, BigDecimal> =
        snapshot.rateFields
            .filter { it.isEditable && it.currencyCode in snapshot.rateInputs }
            .associate { it.currencyCode to moneyParser.parseRate(it.input) }

    /** With saving on, the typed rates that differ from the saved ones (or have none saved yet). */
    private fun ratesToKeep(snapshot: AddTransactionUiState, rates: Map<String, BigDecimal>): Map<String, BigDecimal> {
        if (!snapshot.saveTransactionRates) return emptyMap()
        val saved = snapshot.fx?.ratesToBase.orEmpty()
        return rates.filter { (code, rate) -> saved[code]?.compareTo(rate) != 0 }
    }

    private fun requireCategory(snapshot: AddTransactionUiState): Long =
        snapshot.selectedCategoryId ?: throw MissingSelectionException(UiMessage.CategoryRequired)

    private fun requireDestAccount(snapshot: AddTransactionUiState): Long =
        snapshot.destAccountId ?: throw MissingSelectionException(UiMessage.DestinationAccountRequired)
}
