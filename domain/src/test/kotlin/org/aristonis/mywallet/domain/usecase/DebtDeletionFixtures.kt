package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import java.time.LocalDate

internal val deletionDay: LocalDate = LocalDate.of(2026, 10, 11)
internal fun deletionDebt(side: DebtSide = DebtSide.I_OWE, code: String = "USD") =
    Debt(1, "Ali", side, code, deletionDay.plusDays(7), "rent")
internal fun deletionOpening(debt: Debt, id: Long = 1, amount: String = "10", date: LocalDate = deletionDay) =
    Transaction.OwedBefore(id, debt.id, debt.side, Money.of(amount, debt.currencyCode), date, "original loan")
internal fun deletionPayment(debt: Debt, id: Long = 2, amount: String = "4", date: LocalDate = deletionDay) =
    Transaction.Repayment(id = id, side = debt.side, accountId = 1, debtId = debt.id,
        accountAmount = Money.of(amount, debt.currencyCode), principal = Money.of(amount, debt.currencyCode), date = date)

internal fun SaveEntryRig.deletionEntries(): EntryRepository = object : EntryRepository by entries {
    override suspend fun entryIdsOnDebt(debtId: Long, excludingKinds: Set<String>): List<Long> =
        entries.rows.value.filter { row -> row.header.kind !in excludingKinds && row.legs.any { (it.target as? LegTarget.Debt)?.id == debtId } }
            .sortedWith(compareByDescending<org.aristonis.mywallet.domain.ledger.EntryParts> { it.header.date }.thenByDescending { it.header.id })
            .map { it.header.id }
    override fun observeAll(): Flow<List<Transaction>> = error("deletion must use one-shot reads")
    override fun observeOnDebt(debtId: Long): Flow<List<Transaction>> = error("deletion must use one-shot reads")
}

internal fun SaveEntryRig.retainRawIncome(debt: Debt, amount: String = "3", id: Long = 10) {
    val income = Transaction.Income(id, 1, Money.of("20", debt.currencyCode), 5, date = deletionDay).toParts()
    entries.seedRaw(income.copy(legs = income.legs + Leg(LegRole.DEDUCTION_DEBT, 0,
        LegTarget.Debt(debt.id, debt.side), Money.of(amount, debt.currencyCode))))
}
