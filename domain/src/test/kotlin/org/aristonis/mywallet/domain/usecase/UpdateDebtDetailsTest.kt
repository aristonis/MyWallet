package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.usecase.fake.DirectTransactionRunner
import org.aristonis.mywallet.domain.usecase.fake.FakeDebtRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateDebtDetailsTest {
    @Test fun savesAndClearsDueDateAndNoteWithoutChangingDebtIdentity() = runTest {
        val original = queryDebt(1)
        val debts = FakeDebtRepository(listOf(original))
        val update = UpdateDebtDetails(DirectTransactionRunner(), debts)
        update(1, queryDay, "rent")
        assertEquals(original.copy(dueDate = queryDay, note = "rent"), debts.stored.single())
        update(1, null, null)
        assertEquals(original, debts.stored.single())
    }

    @Test(expected = WalletException.DebtNotFound::class)
    fun missingDebtIsReportedInsteadOfSilentlyIgnoringTheUpdate() = runTest {
        UpdateDebtDetails(DirectTransactionRunner(), FakeDebtRepository())(99, queryDay, "rent")
    }

    @Test(expected = WalletException.DebtNotFound::class)
    fun looksUpTheDebtInsideTheTransactionAfterAnInterveningDeletion() = runTest {
        val debts = FakeDebtRepository(listOf(queryDebt(1)))
        val runner = object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                debts.restoreSnapshot(emptyList())
                return block()
            }
        }
        UpdateDebtDetails(runner, debts)(1, queryDay, "rent")
    }
}
