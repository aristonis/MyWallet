package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.LedgerQueries

/** Debt reference checks and remaining reads shared by save, delete and undo inside their runner. */
class DebtEntryGuard(private val debts: DebtRepository, private val ledger: LedgerQueries) {
    suspend fun requireReference(target: LegTarget.Debt, currencyCode: String): Debt {
        val debt = requireDebt(target.id)
        if (debt.side != target.side) throw WalletException.DebtSideMismatch(target.id)
        if (debt.currencyCode != currencyCode) {
            throw WalletException.CurrencyMismatch(currencyCode, debt.currencyCode)
        }
        return debt
    }

    suspend fun requireDebt(id: Long): Debt = debts.findById(id) ?: throw WalletException.DebtNotFound(id)

    suspend fun remaining(ids: Set<Long>): Map<Long, DebtBalance> =
        if (ids.isEmpty()) emptyMap() else DebtRemaining.byDebt(ledger.debtLegs(ids))

    /** Re-read after writing so every debt the change left behind is checked before commit. */
    suspend fun check(ids: Set<Long>, before: Map<Long, DebtBalance> = emptyMap()): Map<Long, DebtBalance> {
        val balances = remaining(ids)
        ids.forEach { id ->
            val debt = requireDebt(id)
            DebtGuard.check(debt, balances[id]?.remaining ?: Money.zero(debt.currencyCode), before[id]?.remaining)
        }
        return balances
    }

    suspend fun requireReferences(legs: List<Leg>) {
        legs.forEach { leg ->
            val target = leg.target as? LegTarget.Debt ?: return@forEach
            requireReference(target, leg.amount.currencyCode)
        }
    }
}
