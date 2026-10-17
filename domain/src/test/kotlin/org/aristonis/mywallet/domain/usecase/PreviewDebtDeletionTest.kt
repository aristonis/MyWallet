package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewDebtDeletionTest {
    @Test fun listsOnlyOwnEntriesAndCalculatesTheRemainingRetainedIncomeWithoutWriting() = runTest {
        val debt = deletionDebt()
        val opening = deletionOpening(debt)
        val paid = deletionPayment(debt)
        val rig = SaveEntryRig(initialEntries = listOf(opening, paid), initialDebts = listOf(debt), atomic = true)
        rig.retainRawIncome(debt, "3")
        val before = rig.entries.rows.value
        val preview = PreviewDebtDeletion(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(1)
        assertEquals(debt, preview.debt)
        assertEquals(listOf(paid, opening), preview.entries)
        assertEquals(listOf(10L), preview.retainedEntryIds)
        assertEquals(Money.of("3", "USD"), preview.remainingAfter)
        assertEquals(before, rig.entries.rows.value)
        assertEquals(listOf(debt), rig.debts.stored)
    }

    @Test fun hiddenAndSettledDebtsCanBothBePreviewed() = runTest {
        val debt = deletionDebt(DebtSide.OWED_TO_ME)
        val hidden = SaveEntryRig(initialDebts = listOf(debt), atomic = true)
        val empty = PreviewDebtDeletion(hidden.runner, hidden.debts, hidden.deletionEntries(), hidden.ledger)(1)
        assertTrue(empty.entries.isEmpty())
        assertEquals(Money.zero("USD"), empty.remainingAfter)
        val settled = SaveEntryRig(initialEntries = listOf(deletionOpening(debt), deletionPayment(debt, amount = "10")),
            initialDebts = listOf(debt), atomic = true)
        val preview = PreviewDebtDeletion(settled.runner, settled.debts, settled.deletionEntries(), settled.ledger)(1)
        assertEquals(2, preview.entries.size)
        assertEquals(Money.zero("USD"), preview.remainingAfter)
    }

    @Test(expected = WalletException.DebtNotFound::class)
    fun aMissingDebtFailsWithTheTypedError() = runTest {
        val rig = SaveEntryRig(atomic = true)
        PreviewDebtDeletion(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(99)
    }
    @Test fun retainsPaidLaterExpenseAndItsDebtLegsWithoutDecodingIt() = runTest {
        val debt = deletionDebt()
        val expense = org.aristonis.mywallet.domain.model.Transaction.Expense(10, 1,
            Money.of("1000", "USD"), 7, date = deletionDay,
            owed = Money.of("700", "USD"), owedDebtId = debt.id)
        val rig = SaveEntryRig(initialEntries = listOf(deletionOpening(debt), expense),
            initialDebts = listOf(debt), atomic = true)
        val guarded = object : org.aristonis.mywallet.domain.port.EntryRepository by rig.deletionEntries() {
            override suspend fun findById(id: Long): org.aristonis.mywallet.domain.model.Transaction? {
                check(id != expense.id) { "retained expense must not be decoded" }
                return rig.entries.findById(id)
            }
        }
        val preview = PreviewDebtDeletion(rig.runner, rig.debts, guarded, rig.ledger)(debt.id)
        assertEquals(listOf(1L), preview.entries.map { it.id })
        assertEquals(listOf(expense.id), preview.retainedEntryIds)
        assertEquals(Money.of("700", "USD"), preview.remainingAfter)
    }
}
