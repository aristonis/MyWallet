package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.NameKey
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.usecase.fake.DirectTransactionRunner
import org.aristonis.mywallet.domain.usecase.fake.FakeDebtRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RenameDebtTest {
    @Test fun trimsTheNewNameAndKeepsSideCurrencyDueDateAndNote() = runTest {
        val original = queryDebt(1, due = queryDay).copy(note = "rent")
        val debts = FakeDebtRepository(listOf(original))
        RenameDebt(DirectTransactionRunner(), debts)(1, " Ali ")
        assertEquals(original.copy(counterparty = "Ali"), debts.stored.single())
        assertEquals(NameKey.of("Ali"), debts.stored.single().key)
    }

    @Test fun caseOnlyRenameKeepsTheSameDebt() = runTest {
        val original = queryDebt(1).copy(counterparty = "Ali")
        val debts = FakeDebtRepository(listOf(original))
        RenameDebt(DirectTransactionRunner(), debts)(1, "ALI")
        assertEquals(original.copy(counterparty = "ALI"), debts.stored.single())
    }

    @Test fun refusesANameUsedByAHiddenDebtOnTheSameSideAndCurrency() = runTest {
        val first = queryDebt(1).copy(counterparty = "Ali")
        val other = queryDebt(2).copy(counterparty = "Omar")
        val debts = FakeDebtRepository(listOf(first, other))
        try {
            RenameDebt(DirectTransactionRunner(), debts)(2, " ali ")
            fail("expected DebtNameTaken")
        } catch (_: WalletException.DebtNameTaken) {
            assertEquals(listOf(first, other), debts.stored)
        }
    }

    @Test fun permitsTheSameNameOnAnotherSideOrCurrency() = runTest {
        val debts = FakeDebtRepository(listOf(queryDebt(1).copy(counterparty = "Ali"),
            queryDebt(2, side = DebtSide.OWED_TO_ME), queryDebt(3, "EUR")))
        val rename = RenameDebt(DirectTransactionRunner(), debts)
        rename(2, "Ali")
        rename(3, "Ali")
        assertEquals(listOf("Ali", "Ali", "Ali"), debts.stored.map { it.counterparty })
    }

    @Test(expected = WalletException.NameBlank::class)
    fun rejectsABlankName() = runTest {
        RenameDebt(DirectTransactionRunner(), FakeDebtRepository(listOf(queryDebt(1))))(1, " \u200F ")
    }

    @Test(expected = WalletException.DebtNotFound::class)
    fun missingDebtIsReported() = runTest {
        RenameDebt(DirectTransactionRunner(), FakeDebtRepository())(99, "Ali")
    }

    @Test(expected = WalletException.DebtNotFound::class)
    fun checksExistenceInsideTheTransactionAfterAnInterveningDeletion() = runTest {
        val debts = FakeDebtRepository(listOf(queryDebt(1)))
        val runner = object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                debts.restoreSnapshot(emptyList())
                return block()
            }
        }
        RenameDebt(runner, debts)(1, "Ali")
    }
}
