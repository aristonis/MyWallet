package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.DebtDetail
import org.aristonis.mywallet.domain.model.DebtSummary
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.service.DebtAmountsFromHistory
import org.aristonis.mywallet.domain.service.observeToday
import java.time.LocalDate

/** Hidden and negative debts remain reachable by id; a missing debt emits null. */
class ObserveDebt(
    private val debts: DebtRepository,
    private val ledger: LedgerQueries,
    private val entries: EntryRepository,
    private val today: Flow<LocalDate> = observeToday(),
) {
    operator fun invoke(id: Long): Flow<DebtDetail?> =
        combine(debts.observeAll(), ledger.observeDebtLegs(), entries.observeOnDebt(id), today) { rows, legs, history, date ->
            val debt = rows.firstOrNull { it.id == id } ?: return@combine null
            val balance = DebtRemaining.byDebt(legs)[id]
            val summary = DebtSummary.of(debt, balance?.remaining ?: Money.zero(debt.currencyCode), balance != null, date)
            DebtDetail(summary, history, DebtAmountsFromHistory.calculate(id, debt.currencyCode, history))
        }
}
