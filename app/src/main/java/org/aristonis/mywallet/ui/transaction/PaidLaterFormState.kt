package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyParseException
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.RefusedInputException
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import java.time.LocalDate

/** Paid now stays as typed when the purchase amount changes; owed is always derived from the pair. */
data class PaidLaterFormState(
    val enabled: Boolean = false,
    val paidNowInput: String = "",
    val counterparty: String = "",
    val debtId: Long? = null,
    val dueDate: LocalDate? = null,
    val paidNow: Money? = null,
    val owed: Money? = null,
    val error: UiMessage? = null,
) {
    val feeEnabled: Boolean get() = !enabled || paidNow?.isPositive == true
    val allowsSave: Boolean get() = !enabled ||
        (error == null && owed?.isPositive == true && counterparty.isNotBlank())

    fun offeredDebts(debts: List<Debt>, currencyCode: String?): List<Debt> =
        debts.filter { it.side == DebtSide.I_OWE && it.currencyCode == currencyCode }
}

internal class PaidLaterForm(private val parser: MoneyParser) {
    fun recalculated(state: PaidLaterFormState, amountInput: String, currencyCode: String?): PaidLaterFormState {
        val cleared = state.copy(paidNow = null, owed = null, error = null)
        if (!state.enabled || currencyCode == null || amountInput.isBlank()) return cleared
        val amount = try { parser.parseAmount(amountInput, currencyCode) }
            catch (_: MoneyParseException) { return cleared }
        return try {
            val paid = parser.parseOpeningBalance(state.paidNowInput, currencyCode)
            when {
                paid.isNegative -> cleared.copy(error = UiMessage.AmountNotPositive)
                paid >= amount -> cleared.copy(error = UiMessage.PaidNowNotBelowAmount)
                else -> cleared.copy(paidNow = paid, owed = amount - paid)
            }
        } catch (e: MoneyParseException) { cleared.copy(error = e.toUiMessage()) }
    }

    fun onCurrencyChanged(state: PaidLaterFormState, old: String?, new: String?): PaidLaterFormState =
        if (old == new) state else state.copy(paidNowInput = "", counterparty = "", debtId = null,
            dueDate = null, paidNow = null, owed = null, error = null)

    fun fromEntry(entry: Transaction, debt: Debt?): PaidLaterFormState {
        if (entry !is Transaction.Expense) return PaidLaterFormState()
        val owed = entry.owed ?: return PaidLaterFormState()
        return PaidLaterFormState(enabled = true, paidNowInput = parser.toInputString((entry.amount - owed).amount),
            counterparty = debt?.counterparty.orEmpty(), debtId = entry.owedDebtId, dueDate = debt?.dueDate)
    }

    fun forSave(entry: Transaction, state: PaidLaterFormState): BuiltEntry {
        if (entry !is Transaction.Expense) return BuiltEntry(entry)
        val cleared = entry.copy(owed = null, owedDebtId = null)
        if (!state.enabled) return BuiltEntry(cleared)
        state.error?.let { throw RefusedInputException(it) }
        if (state.counterparty.isBlank()) throw RefusedInputException(UiMessage.NameRequired)
        val owed = state.owed ?: throw RefusedInputException(UiMessage.AmountMissing)
        return BuiltEntry(cleared.copy(owed = owed, owedDebtId = state.debtId),
            DebtRef(state.counterparty, DebtSide.I_OWE, entry.amount.currencyCode).takeIf { state.debtId == null })
    }
}
