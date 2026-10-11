package org.aristonis.mywallet.ui.debt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.model.DebtDetail
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CategoryRoleRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.usecase.ObserveDebt
import org.aristonis.mywallet.domain.usecase.RecordRepayment
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import org.aristonis.mywallet.ui.transaction.PairRate
import org.aristonis.mywallet.ui.transaction.RateField
import java.time.LocalDate
import javax.inject.Inject

/** Immutable screen state. Pricing stays in [RecordRepayment], while this state only captures input. */
data class RecordRepaymentUiState(
    val debt: DebtDetail? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val fx: FxSnapshot? = null,
    val accountId: Long? = null,
    val principalInput: String = "",
    val accountPrincipalInput: String = "",
    /** True once the owner typed the account amount, so a later principal change must not clear it. */
    val accountAmountTouched: Boolean = false,
    val interestInput: String = "",
    val interestCategoryId: Long? = null,
    val interestSubCategoryId: Long? = null,
    val defaultInterestCategoryId: Long? = null,
    val editingRepaymentId: Long? = null,
    val loadedRepayment: Transaction.Repayment? = null,
    val date: LocalDate,
    val note: String = "",
    val payRest: Boolean = false,
    val rateInputs: Map<String, String> = emptyMap(),
    val rateFields: List<RateField> = emptyList(),
    val pairRate: PairRate? = null,
    val rateRequired: Boolean = false,
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val saved: Boolean = false,
    val error: UiMessage? = null,
    /** The debt's remaining, formatted; null until the debt loads. */
    val remainingDisplay: String? = null,
    /** What the chosen account moves for this payment, formatted; null when it cannot be priced. */
    val accountMovesDisplay: String? = null,
) {
    val selectedAccount: Account? get() = accounts.firstOrNull { it.id == accountId }
    val debtCurrency: String? get() = debt?.summary?.debt?.currencyCode
    val interestCategories: List<Category>
        get() = categories.filter { !it.isSubCategory && it.kind == interestCategoryKind(debt?.summary?.debt?.side) }

    /** Why Save cannot work yet when the cause is a missing account; null otherwise. */
    val blockedReason: UiMessage? get() = if (!isLoading && selectedAccount == null) UiMessage.AccountRequired else null
    val canSubmit: Boolean
        get() = (editingRepaymentId != null || debt?.summary?.remaining?.isPositive == true) &&
            selectedAccount != null && !isSubmitting && !saved &&
            !rateRequired && (payRest || principalInput.isNotBlank() || interestInput.isNotBlank())
}

/** Routes one repayment through the atomic domain use case; it never computes an allowance itself. */
@HiltViewModel
class RecordRepaymentViewModel @Inject constructor(
    private val observeDebt: ObserveDebt,
    accounts: AccountRepository,
    categories: CategoryRepository,
    fx: FxRepository,
    private val recordRepayment: RecordRepayment,
    private val parser: MoneyParser,
    private val today: TodaySource,
    formatter: MoneyFormatter,
    private val errors: ErrorReporter,
    private val roles: CategoryRoleRepository,
) : ViewModel() {
    private val math = RecordRepaymentMath(parser, formatter)
    private val mutableState = MutableStateFlow(RecordRepaymentUiState(date = today.now()))
    val state: StateFlow<RecordRepaymentUiState> = mutableState.asStateFlow()
    private val accountFlow = accounts.observeAll()
    private val categoryFlow = categories.observeAll()
    private val fxFlow = fx.observeFx()
    private var reads: Job? = null
    private var loadedId: Long? = null
    private var requestedRepaymentId: Long? = null
    private var loadedRole: CategoryRole? = null
    private var roleJob: Job? = null

    fun load(debtId: Long, repaymentId: Long? = null) {
        if (loadedId == debtId && requestedRepaymentId == repaymentId) return
        loadedId = debtId
        requestedRepaymentId = repaymentId
        loadedRole = null
        reads?.cancel()
        roleJob?.cancel()
        mutableState.value = RecordRepaymentUiState(date = today.now())
        reads = combine(observeDebt(debtId), accountFlow, categoryFlow, fxFlow) { debt, accountList, categoryList, fx ->
            Inputs(debt, accountList, categoryList, fx)
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            errors.report("Repayment form could not read the wallet", failure)
            mutableState.update { it.copy(isLoading = false, error = failure.toUiMessage()) }
        }.onEach { inputs ->
            mutate { current -> current.withInputs(inputs) }
            inputs.debt?.summary?.debt?.side?.let(::loadInterestRole)
        }.launchIn(viewModelScope)
    }

    fun selectAccount(id: Long) = mutate { current ->
        val changesCurrency = current.accounts.firstOrNull { it.id == id }?.currencyCode !=
            current.selectedAccount?.currencyCode
        current.copy(
            accountId = id,
            accountPrincipalInput = "",
            accountAmountTouched = false,
            interestInput = if (changesCurrency) "" else current.interestInput,
            error = null,
        )
    }

    fun setPrincipal(input: String) = mutate { current ->
        // The account amount typed for the old principal would silently set a new rate; the stored rate is kept.
        val clearAccount = current.editingRepaymentId != null && !current.accountAmountTouched
        current.copy(
            principalInput = input,
            payRest = false,
            accountPrincipalInput = if (clearAccount) "" else current.accountPrincipalInput,
            error = null,
        )
    }

    fun setAccountPrincipal(input: String) =
        mutate { it.copy(accountPrincipalInput = input, accountAmountTouched = true, error = null) }
    fun setInterest(input: String) = mutate { it.copy(interestInput = input, error = null) }
    fun selectInterestCategory(id: Long) =
        mutate { it.copy(interestCategoryId = id, interestSubCategoryId = null) }
    fun setRate(code: String, input: String) = mutate { it.copy(rateInputs = it.rateInputs + (code to input)) }
    fun setDate(date: LocalDate) = mutableState.update { it.copy(date = date) }
    fun setNote(note: String) = mutableState.update { it.copy(note = note) }

    /** Pay-the-rest leaves principal null so the transaction reads the fresh remaining amount itself. */
    fun payTheRest(on: Boolean) = mutate {
        if (on) {
            it.copy(payRest = true, principalInput = "", accountPrincipalInput = "", error = null)
        } else {
            it.copy(payRest = false, error = null)
        }
    }

    fun submit() {
        val snapshot = state.value
        if (!snapshot.canSubmit) return
        mutableState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                save(snapshot)
                mutableState.update { it.copy(isSubmitting = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun save(form: RecordRepaymentUiState) {
        val plan = math.plan(form)
        recordRepayment(
            debtId = checkNotNull(form.debt).summary.debt.id,
            accountId = checkNotNull(form.accountId),
            principal = plan.principal,
            date = form.date,
            typedRates = plan.typedRates,
            accountPrincipal = plan.accountPrincipal,
            pairRate = plan.pairRate,
            interest = plan.interest,
            interestCategoryId = form.interestCategoryId,
            interestSubCategoryId = form.interestSubCategoryId,
            note = form.note.trim().ifEmpty { null },
            id = form.editingRepaymentId ?: 0,
        )
    }

    private fun fail(failure: Exception) {
        val message = failure.toUiMessage()
        if (message == UiMessage.Unexpected) errors.report("Repayment could not be saved", failure)
        mutableState.update { it.copy(isSubmitting = false, error = message) }
    }

    private fun RecordRepaymentUiState.withInputs(inputs: Inputs): RecordRepaymentUiState {
        val repayment = requestedRepaymentId?.let { id ->
            inputs.debt?.entries?.filterIsInstance<Transaction.Repayment>()?.firstOrNull { it.id == id }
        }
        val populated = if (repayment != null && editingRepaymentId == null) populatedFrom(repayment, parser) else this
        // An archived account stays offered on an edit of its own repayment, so the form can say why it is refused.
        val offered = inputs.accounts.filter { !it.archived || it.id == repayment?.accountId }
        return populated.copy(
            debt = inputs.debt,
            accounts = offered,
            categories = inputs.categories,
            fx = inputs.fx,
            accountId = populated.accountId ?: offered.firstOrNull { !it.archived }?.id,
            isLoading = false,
        )
    }

    private fun mutate(change: (RecordRepaymentUiState) -> RecordRepaymentUiState) = mutableState.update { current ->
        val next = change(current)
        val quote = math.quote(next)
        next.copy(
            rateFields = quote.rateFields,
            pairRate = math.pairRatePreview(next, quote),
            rateRequired = math.rateRequired(next, quote),
            remainingDisplay = math.remainingDisplay(next),
            accountMovesDisplay = math.accountMovesDisplay(next),
        )
    }

    private fun loadInterestRole(side: DebtSide) {
        val role = interestRole(side)
        if (loadedRole == role) return
        loadedRole = role
        roleJob?.cancel()
        roleJob = viewModelScope.launch {
            val bound = try {
                roles.categoryFor(role)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The default category is a convenience; without it the owner picks one.
                errors.report("Repayment form could not read the interest category role", e)
                null
            }
            mutate { state ->
                val valid = bound?.takeIf { id ->
                    state.categories.any { it.id == id && !it.isSubCategory && it.kind == role.kind }
                }
                state.copy(defaultInterestCategoryId = valid, interestCategoryId = state.interestCategoryId ?: valid)
            }
        }
    }
}

private class Inputs(
    val debt: DebtDetail?,
    val accounts: List<Account>,
    val categories: List<Category>,
    val fx: FxSnapshot,
)

private fun RecordRepaymentUiState.populatedFrom(entry: Transaction.Repayment, parser: MoneyParser) = copy(
    editingRepaymentId = entry.id,
    loadedRepayment = entry,
    accountId = entry.accountId,
    principalInput = parser.toInputString(entry.principal.amount),
    accountPrincipalInput = parser.toInputString(entry.accountPrincipalAmount().amount),
    accountAmountTouched = false,
    interestInput = entry.interest?.let { parser.toInputString(it.amount) }.orEmpty(),
    interestCategoryId = entry.interestCategoryId,
    interestSubCategoryId = entry.interestSubCategoryId,
    date = entry.date,
    note = entry.note.orEmpty(),
)

private fun Transaction.Repayment.accountPrincipalAmount() = interest?.let { accountAmount - it } ?: accountAmount

private fun interestCategoryKind(side: DebtSide?): CategoryKind =
    if (side == DebtSide.I_OWE) CategoryKind.EXPENSE else CategoryKind.INCOME

private fun interestRole(side: DebtSide): CategoryRole =
    if (side == DebtSide.I_OWE) CategoryRole.INTEREST_OUT else CategoryRole.INTEREST_IN
