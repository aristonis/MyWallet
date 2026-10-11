package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.AccountBalance
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.HomeSummary
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.DebtBalance
import org.aristonis.mywallet.domain.service.DebtRemaining

/**
 * Live Home summary in the base currency at today's rates. The caller passes the account balances,
 * so Home reads the account legs once for everything it shows.
 *
 * Pocket is the non-archived accounts, except an owed-when-negative account (a card) below zero.
 * I owe is the I-owe debts still open plus every such card below zero, archived or not, because the
 * money is owed either way. Owed to me is the owed-to-me debts still open. The balance is pocket +
 * owed to me - I owe. A debt below zero is left out of every total, needs no rate, and its id stays available
 * in both summary results so Home can show a warning.
 *
 * A missing rate is a returned [HomeSummary.MissingRate], not a throw, so the flow stays live and
 * resolves once the rate is set. A rate is needed for each non-archived account, each owing card and
 * each debt with something left; a settled debt never blocks the total.
 */
class ComputeHomeSummary(
    private val ledger: LedgerQueries,
    private val fx: FxRepository,
) {
    operator fun invoke(balances: Flow<List<AccountBalance>>): Flow<HomeSummary> =
        combine(balances, ledger.observeDebtLegs(), fx.observeFx()) { accounts, legs, snapshot ->
            summarize(accounts, DebtRemaining.byDebt(legs), snapshot)
        }

    private fun summarize(accounts: List<AccountBalance>, debts: Map<Long, DebtBalance>, snapshot: FxSnapshot): HomeSummary {
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
        val iOwe = total(open.filter { it.side == DebtSide.I_OWE }.map { it.remaining } + owingCards.map { -it.balance })
        return HomeSummary.Amounts(pocket, owedToMe, iOwe, pocket + owedToMe - iOwe, belowZero)
    }

    private fun AccountBalance.isOwing(): Boolean = credit != null && balance.amount.signum() < 0
}
