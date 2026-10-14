package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.domain.model.AccountBalance
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.HomeSummary
import org.aristonis.mywallet.domain.model.MAX_CHART_LEGS
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.NetWorthPoint
import org.aristonis.mywallet.domain.model.NetWorthSeries
import org.aristonis.mywallet.domain.port.DatedLegs
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.NetWorthLegReader
import org.aristonis.mywallet.domain.service.DebtBalance
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.service.HomeSummaryMath
import org.aristonis.mywallet.domain.service.NetWorthBuckets
import java.time.LocalDate

/**
 * Live net worth over a Tracking window, as a restatement of the ledger as it is now at today's
 * rates. A point for day d is today's account balances and debt remainders minus the legs dated
 * after d, run through the same rules as Home ([HomeSummaryMath]) at one rate snapshot. It is not
 * what the app would have shown on d: opening balances, archive flags, edited and deleted entries and
 * rates all reach back into earlier points, and the series changes when they do.
 *
 * The dated legs come from one bounded read starting at the window's first day. More than [maxLegs]
 * of them makes the series [NetWorthSeries.TooManyLegs]; it is never built from part of them. A
 * window that cannot draw a line reads nothing and returns the exact balance now. The caller passes
 * the balances, as for Home, so the account legs are read once for everything on a screen.
 *
 * A missing rate for any point is a returned [NetWorthSeries.MissingRate], so the flow stays live
 * and resolves once the rate is set.
 */
class ComputeNetWorthSeries(
    private val ledger: LedgerQueries,
    private val legs: NetWorthLegReader,
    private val fx: FxRepository,
    private val maxLegs: Int = MAX_CHART_LEGS,
) {
    operator fun invoke(
        balances: Flow<List<AccountBalance>>,
        range: DateRange,
        today: LocalDate,
    ): Flow<NetWorthSeries> = when (val plan = NetWorthBuckets.plan(range, today)) {
        is NetWorthBuckets.Plan.NoTrend -> combine(balances, ledger.observeDebtLegs(), fx.observeFx()) { accounts, debtLegs, snapshot ->
            currentOnly(plan, accounts, debtLegs, snapshot)
        }
        is NetWorthBuckets.Plan.Days -> legs.observeLegsAfter(plan.dates.first(), maxLegs).let { dated ->
            combine(balances, ledger.observeDebtLegs(), fx.observeFx(), dated) { accounts, debtLegs, snapshot, later ->
                trend(plan.dates, accounts, DebtRemaining.byDebt(debtLegs), snapshot, later)
            }
        }
    }

    private fun currentOnly(
        plan: NetWorthBuckets.Plan.NoTrend,
        accounts: List<AccountBalance>,
        debtLegs: List<DebtLeg>,
        snapshot: FxSnapshot,
    ): NetWorthSeries = when (val now = HomeSummaryMath.summarize(accounts, DebtRemaining.byDebt(debtLegs), snapshot)) {
        is HomeSummary.MissingRate -> NetWorthSeries.MissingRate(now.currencyCode)
        is HomeSummary.Amounts -> NetWorthSeries.CurrentOnly(now.balance, plan.reason)
    }

    private fun trend(
        dates: List<LocalDate>,
        accounts: List<AccountBalance>,
        debts: Map<Long, DebtBalance>,
        snapshot: FxSnapshot,
        later: DatedLegs,
    ): NetWorthSeries {
        if (later.overLimit) return NetWorthSeries.TooManyLegs
        val afterByDay = afterEachDay(dates, later)
        val points = dates.map { day ->
            val after = afterByDay.getValue(day)
            val summary = HomeSummaryMath.summarize(
                accounts = accounts.map { it.copy(balance = it.balance - after.accounts.orZero(it.account.id, it.balance)) },
                debts = debts.mapValues { (id, debt) -> debt.copy(remaining = debt.remaining - after.debts.orZero(id, debt.remaining)) },
                snapshot = snapshot,
            )
            when (summary) {
                is HomeSummary.MissingRate -> return NetWorthSeries.MissingRate(summary.currencyCode)
                is HomeSummary.Amounts -> NetWorthPoint(day, summary.balance)
            }
        }
        return NetWorthSeries.Trend(points)
    }

    private fun Map<Long, Money>.orZero(id: Long, like: Money): Money = this[id] ?: Money.zero(like.currencyCode)

    private class After(val accounts: Map<Long, Money>, val debts: Map<Long, Money>)

    /**
     * What the legs dated after each of [dates] add up to, per account and per debt. One pass over the
     * legs, newest first, so the cost does not grow with the number of points.
     */
    private fun afterEachDay(dates: List<LocalDate>, later: DatedLegs): Map<LocalDate, After> {
        val accounts = later.account.sortedByDescending { it.date }
        val debts = later.debt.sortedByDescending { it.date }
        val accountSums = HashMap<Long, Money>()
        val debtSums = HashMap<Long, Money>()
        var nextAccount = 0
        var nextDebt = 0
        return dates.asReversed().associateWith { day ->
            while (nextAccount < accounts.size && accounts[nextAccount].date.isAfter(day)) {
                accountSums.merge(accounts[nextAccount].accountId, accounts[nextAccount].amount, Money::plus)
                nextAccount++
            }
            while (nextDebt < debts.size && debts[nextDebt].date.isAfter(day)) {
                debtSums.merge(debts[nextDebt].debtId, debts[nextDebt].amount, Money::plus)
                nextDebt++
            }
            After(accountSums.toMap(), debtSums.toMap())
        }
    }
}
