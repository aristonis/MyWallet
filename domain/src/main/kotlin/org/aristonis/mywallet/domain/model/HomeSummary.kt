package org.aristonis.mywallet.domain.model

/**
 * What Home shows at the top, in the base currency, or a signal that a rate is still missing. A
 * sealed result keeps the flow alive: once the user sets the missing rate it re-emits as [Amounts].
 */
sealed interface HomeSummary {

    /**
     * @property pocket Money the owner holds: non-archived accounts, minus a card that is owed.
     * @property owedToMe What others still owe the owner.
     * @property iOwe What the owner still owes, on debts and on cards.
     * @property balance [pocket] plus [owedToMe] less [iOwe].
     * @property debtsBelowZero Ids, ascending, of debts whose remaining is below zero. They are left
     *   out of every total and need no rate, so Home can point at them instead of showing a wrong sum.
     */
    data class Amounts(
        val pocket: Money,
        val owedToMe: Money,
        val iOwe: Money,
        val balance: Money,
        val debtsBelowZero: List<Long>,
    ) : HomeSummary

    data class MissingRate(val currencyCode: String) : HomeSummary
}
