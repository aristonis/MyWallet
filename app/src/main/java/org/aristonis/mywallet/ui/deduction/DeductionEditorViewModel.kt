package org.aristonis.mywallet.ui.deduction

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.data.format.MoneyParseException
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.NameKey
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.DeductionItemRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.SaveDeductionDefinition
import org.aristonis.mywallet.ui.message.MissingSelectionException
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import org.aristonis.mywallet.ui.navigation.ARG_ID
import javax.inject.Inject

/**
 * Editable defaults for one reusable deduction. A null [expenseCategoryId] means automatic creation,
 * unless [expenseCategoryMissing] says the saved category vanished and no choice has been made yet.
 */
data class DeductionEditorUiState(
    val name: String = "",
    val value: String = "",
    val mode: DeductionMode = DeductionMode.PERCENT,
    val timing: DeductionTiming = DeductionTiming.NOW,
    val currencyCode: String? = null,
    val expenseCategoryId: Long? = null,
    val expenseSubCategoryId: Long? = null,
    val autoAddCategoryIds: Set<Long> = emptySet(),
    val enabled: Boolean = true,
    val currencies: List<Currency> = emptyList(),
    val expenseCategories: List<Category> = emptyList(),
    val incomeCategories: List<Category> = emptyList(),
    val expenseSubCategories: List<Category> = emptyList(),
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val notFound: Boolean = false,
    val expenseCategoryMissing: Boolean = false,
    val isSubmitting: Boolean = false,
    val saved: Boolean = false,
    val nameError: UiMessage? = null,
    val valueError: UiMessage? = null,
    val error: UiMessage? = null,
) {
    val showsCurrency: Boolean get() = mode == DeductionMode.FIXED
    val canSubmit: Boolean
        get() = !isLoading && !loadFailed && !notFound && !expenseCategoryMissing && !isSubmitting && !saved
}

/** Edits rule defaults without changing any already recorded income. */
@HiltViewModel
class DeductionEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val items: DeductionItemRepository,
    private val categories: CategoryRepository,
    private val currencies: CurrencyRepository,
    private val settings: SettingsRepository,
    private val saveDefinition: SaveDeductionDefinition,
    private val parser: MoneyParser,
    private val errors: ErrorReporter,
) : ViewModel() {
    private val id = handle.get<Long>(ARG_ID) ?: 0L
    private val mutableState = MutableStateFlow(DeductionEditorUiState())
    val state = mutableState.asStateFlow()
    private var sortOrder = 0
    private var initialized = false
    private var expenseChoices: List<Category> = emptyList()
    private var readJob: Job? = null

    init { retry() }

    /** Reloads choices after a failed read, keeping edits already made. */
    fun retry() {
        readJob?.cancel()
        mutableState.update { it.copy(isLoading = true, loadFailed = false) }
        readJob = viewModelScope.launch {
            try {
                if (!initialized) initialize()
                combine(categories.observeAll(), currencies.observeAll()) { categoryList, currencyList ->
                    categoryList to currencyList
                }.collect { (categoryList, currencyList) -> applyChoices(categoryList, currencyList) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: WalletException.DeductionItemNotFound) {
                // The rule was deleted elsewhere; reading again cannot bring it back, so no Retry is offered.
                mutableState.update { it.copy(isLoading = false, notFound = true) }
            } catch (error: Exception) {
                errors.report("Deduction editor choices could not be read", error)
                mutableState.update { it.copy(isLoading = false, loadFailed = true, error = error.toUiMessage()) }
            }
        }
    }

    private fun applyChoices(categoryList: List<Category>, currencyList: List<Currency>) {
        expenseChoices = categoryList
        val expenseCategories = categoryList.filter { it.kind == CategoryKind.EXPENSE && !it.isSubCategory }
        val incomeCategories = categoryList.filter { it.kind == CategoryKind.INCOME && !it.isSubCategory }
        val expenseIds = expenseCategories.map(Category::id).toSet()
        val incomeIds = incomeCategories.map(Category::id).toSet()
        mutableState.update { current ->
            val subCategories = categoryList.filter {
                it.kind == CategoryKind.EXPENSE && it.parentId == current.expenseCategoryId && it.isSubCategory
            }
            // A vanished category is not silently swapped for automatic creation, which would book the
            // rule to a different category; the owner must pick one or choose automatic again.
            val lost = current.expenseCategoryId?.let { it !in expenseIds } == true ||
                current.expenseSubCategoryId?.let { id -> subCategories.none { it.id == id } } == true
            current.copy(
                expenseCategories = expenseCategories,
                expenseSubCategories = if (lost) emptyList() else subCategories,
                incomeCategories = incomeCategories,
                expenseCategoryId = current.expenseCategoryId.takeUnless { lost },
                expenseSubCategoryId = current.expenseSubCategoryId.takeUnless { lost },
                expenseCategoryMissing = current.expenseCategoryMissing || lost,
                autoAddCategoryIds = current.autoAddCategoryIds.intersect(incomeIds),
                currencies = currencyList,
                isLoading = false,
                loadFailed = false,
            )
        }
    }

    private suspend fun initialize() {
        val base = settings.observe().first().baseCurrencyCode
        val item = if (id > 0) items.findById(id) ?: throw WalletException.DeductionItemNotFound(id) else null
        val category = item?.let { categories.findById(it.expenseCategoryId) }
        sortOrder = item?.sortOrder ?: 0
        mutableState.update { current ->
            current.copy(name = item?.name.orEmpty(), value = item?.value?.let(parser::toInputString).orEmpty(),
                mode = item?.mode ?: DeductionMode.PERCENT, timing = item?.timing ?: DeductionTiming.NOW,
                currencyCode = item?.valueCurrencyCode ?: base,
                expenseCategoryId = category?.parentId ?: item?.expenseCategoryId,
                expenseSubCategoryId = category?.takeIf { it.isSubCategory }?.id,
                autoAddCategoryIds = item?.autoAddCategoryIds ?: emptySet(), enabled = item?.enabled ?: true)
        }
        initialized = true
    }

    /** Updates the name and clears its previous field refusal. */
    fun setName(name: String) = mutableState.update { it.copy(name = name, nameError = null, error = null) }
    /** Updates the exact numeric input. */
    fun setValue(value: String) = mutableState.update { it.copy(value = value, valueError = null, error = null) }
    /** Changes how the value is interpreted, retaining the entered digits. */
    fun selectMode(mode: DeductionMode) = mutableState.update { it.copy(mode = mode, valueError = null) }
    /** Picks the fixed amount's currency. */
    fun selectCurrency(code: String) = mutableState.update { it.copy(currencyCode = code, error = null) }
    /** Picks when the money is paid. */
    fun selectTiming(timing: DeductionTiming) = mutableState.update { it.copy(timing = timing) }
    /** Selects an expense category, or automatic creation when null; either answers a missing-category notice. */
    fun selectExpenseCategory(id: Long?) = mutableState.update {
        it.copy(expenseCategoryId = id, expenseSubCategoryId = null,
            expenseSubCategories = expenseChoices.filter { category ->
                category.kind == CategoryKind.EXPENSE && category.isSubCategory && category.parentId == id
            }, expenseCategoryMissing = false, error = null)
    }
    /** Selects an optional child of the current expense category. */
    fun selectExpenseSubCategory(id: Long?) = mutableState.update {
        it.copy(expenseSubCategoryId = id, error = null)
    }
    /** Selects whether future incomes offer the rule. */
    fun setEnabled(enabled: Boolean) = mutableState.update { it.copy(enabled = enabled) }
    /** Toggles a top-level income category's automatic selection. */
    fun toggleAutoAdd(id: Long) = mutableState.update {
        it.copy(autoAddCategoryIds = if (id in it.autoAddCategoryIds) it.autoAddCategoryIds - id
            else it.autoAddCategoryIds + id)
    }

    /** Validates the input, then writes the rule and any default category atomically. */
    fun submit() {
        val draft = state.value
        if (!draft.canSubmit) return
        val item = try { itemFrom(draft) } catch (error: Exception) { refuse(error); return }
        mutableState.update { it.copy(isSubmitting = true, error = null, nameError = null, valueError = null) }
        viewModelScope.launch {
            try {
                validateSelections(draft)
                saveDefinition(item, draft.expenseSubCategoryId ?: draft.expenseCategoryId)
                mutableState.update { it.copy(saved = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                refuse(error)
            } finally {
                mutableState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    private fun itemFrom(draft: DeductionEditorUiState): DeductionItem {
        if (draft.name.isBlank()) throw MissingSelectionException(UiMessage.NameRequired)
        NameKey.of(draft.name)
        val code = draft.currencyCode ?: throw MissingSelectionException(UiMessage.CurrencyRequired)
        val amount = parser.parseOpeningBalance(draft.value, code).amount
        return DeductionItem(id, draft.name.trim(), draft.mode, amount,
            if (draft.showsCurrency) code else null, draft.timing, draft.expenseCategoryId ?: 0,
            draft.autoAddCategoryIds, draft.enabled, sortOrder)
    }

    private suspend fun validateSelections(draft: DeductionEditorUiState) {
        if (draft.showsCurrency && currencies.findByCode(draft.currencyCode.orEmpty()) == null) {
            throw MissingSelectionException(UiMessage.CurrencyRequired)
        }
        draft.expenseSubCategoryId?.let { id ->
            val category = categories.findById(id) ?: throw WalletException.CategoryNotFound(id)
            if (category.parentId != draft.expenseCategoryId) throw WalletException.CategoryWrongKind(id)
        }
        for (categoryId in draft.autoAddCategoryIds) {
            val category = categories.findById(categoryId) ?: throw WalletException.CategoryNotFound(categoryId)
            if (category.kind != CategoryKind.INCOME) throw WalletException.CategoryWrongKind(categoryId)
            if (category.isSubCategory) throw WalletException.CategoryNotTopLevel(categoryId)
        }
    }

    private fun refuse(error: Exception) {
        if (error is CancellationException) throw error
        val message = error.toUiMessage()
        mutableState.update { current ->
            when {
                message == UiMessage.NameRequired || message == UiMessage.DeductionItemNameTaken ||
                    error is WalletException.NameBlank ->
                    current.copy(nameError = message)
                error is MoneyParseException || error is WalletException.DeductionOutOfRange ||
                    error is WalletException.DeductionValueNotPositive -> current.copy(valueError = message)
                else -> current.copy(error = message)
            }
        }
        if (message == UiMessage.Unexpected) errors.report("Deduction rule could not be saved", error)
    }
}
