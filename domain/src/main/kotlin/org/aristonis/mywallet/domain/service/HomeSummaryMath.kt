package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.AccountBalance
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.HomeSummary
import org.aristonis.mywallet.domain.model.Money

/**
 * The one place the Home totals are worked out, from balances and debts that are already known. Home
 * feeds it today's, and the net worth series feeds it the same figures restated for an earlier day, so
 * the two can never disagree about what counts or which way it signs.
 *
 * Pocket is the non-archived accounts, except an owed-when-negative account (a card) below zero.
 * I owe is the I-owe debts still open plus every such card below zero, archived or not, because the
 * money is owed either way. Owed to me is the owed-to-me debts still open. A debt below zero is left
 * out of every total, needs no rate, and is named in the result. A rate is needed for each
 * non-archived account, each owing card and each debt with something left.
 */
object HomeSummaryMath {

    fun summarize(
        accounts: List<AccountBalance>,
        debts: Map<Long, DebtBalance>,
        snapshot: FxSnapshot,
    ): HomeSummary {
        val base = snapshot.baseCurrencyCode
        val owingCards = accounts.filter { it.isOwing() }
        val inPocket = accounts.filter { !it.account.archived && !it.isOwing() }
        val open = debts.values.filter { it.remaining.amount.signum() > 0 }

        val belowZero = debts.filterValues { it.remaining.amount.signum() < 0 }.keys.sorted()
        val missing = (accounts.filter { !it.account.archived }.map { it.balance.currencyCode } +
            owingCards.map { it.balance.currencyCode } + open.map { it.remaining.currencyCode })
            .firstOrNull { it != base && it !in snapshot.ratesToBase }
        if (missing != null) return HomeSummary.MissingRate(missing, belowZero)

        val converter = CurrencyConverter(base, snapshot.ratesToBase, snapshot.decimalPlaces)
        fun total(amounts: List<Money>) = amounts.fold(Money.zero(base)) { sum, m -> sum + converter.convert(m, base) }

        val pocket = total(inPocket.map { it.balance })
        val owedToMe = total(open.filter { it.side == DebtSide.OWED_TO_ME }.map { it.remaining })
        val iOwe = total(
            open.filter { it.side == DebtSide.I_OWE }.map { it.remaining } + owingCards.map { -it.balance },
        )
        return HomeSummary.Amounts(pocket, owedToMe, iOwe, pocket + owedToMe - iOwe, belowZero)
    }

    private fun AccountBalance.isOwing(): Boolean = credit != null && balance.amount.signum() < 0
}
