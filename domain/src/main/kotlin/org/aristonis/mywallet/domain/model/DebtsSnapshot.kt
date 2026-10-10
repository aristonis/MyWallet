package org.aristonis.mywallet.domain.model

/** Visible debts in their own currencies, with their positive balances totaled in the base currency. */
data class DebtsSnapshot(val debts: List<DebtSummary>, val totals: DebtTotals)

/** A missing rate affects the totals while each debt remains readable in its own currency. */
sealed interface DebtTotals {
    data class Amounts(val iOwe: Money, val owedToMe: Money) : DebtTotals

    data class MissingRate(val currencyCode: String) : DebtTotals
}
