package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DebtState
import org.aristonis.mywallet.domain.model.DebtSummary
import org.aristonis.mywallet.domain.model.DebtTotals
import org.aristonis.mywallet.domain.model.DebtsSnapshot
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.DebtBalance
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.service.observeToday
import java.time.LocalDate

/**
 * Live debt rows and side totals. Hidden debts are omitted; settled and negative balances stay
 * visible. Negative balances contribute nothing to either total and never require an exchange rate.
 */
class ObserveDebts(
    private val debts: DebtRepository,
    private val ledger: LedgerQueries,
    private val fx: FxRepository,
    private val today: Flow<LocalDate> = observeToday(),
) {
    operator fun invoke(): Flow<DebtsSnapshot> =
        combine(debts.observeAll(), ledger.observeDebtLegs(), fx.observeFx(), today) { rows, legs, snapshot, date ->
            val balances = DebtRemaining.byDebt(legs)
            val summaries = rows.map { debt ->
                DebtSummary.of(debt, balances[debt.id]?.remaining ?: Money.zero(debt.currencyCode), debt.id in balances, date)
            }.filter { it.state != DebtState.HIDDEN }
            DebtsSnapshot(summaries, totals(balances.values.toList(), snapshot))
        }

    /** Uses the same per-debt conversion and rounding as Home's debt contribution. */
    private fun totals(balances: List<DebtBalance>, snapshot: FxSnapshot): DebtTotals {
        val base = snapshot.baseCurrencyCode
        val positive = balances.filter { it.remaining.isPositive }
        val missing = positive.map { it.remaining.currencyCode }.firstOrNull { !snapshot.hasRateFor(it) }
        if (missing != null) return DebtTotals.MissingRate(missing)

        val converter = CurrencyConverter(base, snapshot.ratesToBase, snapshot.decimalPlaces)
        fun total(side: DebtSide): Money = positive.filter { it.side == side }
            .fold(Money.zero(base)) { sum, balance -> sum + converter.convert(balance.remaining, base) }
        return DebtTotals.Amounts(total(DebtSide.I_OWE), total(DebtSide.OWED_TO_ME))
    }
}
