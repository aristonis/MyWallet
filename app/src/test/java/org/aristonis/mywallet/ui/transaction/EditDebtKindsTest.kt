package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.aristonis.mywallet.domain.error.WalletException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class EditDebtKindsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()
    private val day = DebtFormTestFixture.day

    @Test fun eachNewKindLoadsDebtMetadataAndSavesANoteEdit() = runTest {
        entries.forEach { entry ->
            val f = DebtFormTestFixture(initial = listOf(entry))
            advanceUntilIdle()
            f.edit.load(entry.id)
            advanceUntilIdle()
            assertTrue(entry.toString(), f.edit.state.value.canEdit)
            assertFalse(entry.toString(), f.edit.state.value.readOnly)
            val debtId = f.edit.state.value.debtId!!
            assertEquals(f.debts.findById(debtId)!!.counterparty, f.edit.state.value.counterparty)
            f.edit.setNote("receipt")
            f.edit.submit()
            advanceUntilIdle()
            assertNull(entry.toString(), f.edit.state.value.error)
            val saved = f.use.entries.stored.single()
            assertEquals(entry.id, saved.id)
            assertEquals("receipt", saved.note)
            assertEquals(EntryFormRules.forEntry(entry)!!.type, EntryFormRules.forEntry(saved)!!.type)
        }
    }

    @Test fun aCategoryDebtEntryUsesItsStoredCurrencyAndRateEvenWithAnUnrelatedAccount() = runTest {
        val entry = Transaction.EarnedUnpaid(10, 8, DebtFormTestFixture.eur("50"), 5,
            date = day, rateToBase = BigDecimal("11000"))
        val f = DebtFormTestFixture(initial = listOf(entry))
        advanceUntilIdle()
        f.edit.load(10)
        advanceUntilIdle()
        f.edit.selectAccount(1)
        assertEquals("EUR", f.edit.state.value.rateFields.single().currencyCode)
        assertEquals("11000", f.edit.state.value.rateFields.single().input)
        f.edit.setNote("receipt")
        f.edit.submit()
        advanceUntilIdle()
        assertEquals(entry.copy(note = "receipt"), f.use.entries.stored.single())
    }

    @Test fun aDebtOnlyKindRemainsEditableWhenNoAccountExists() = runTest {
        val entry = Transaction.OwedBefore(10, 7, DebtSide.I_OWE, DebtFormTestFixture.usd("50"), day)
        val f = DebtFormTestFixture(initial = listOf(entry), accounts = emptyList())
        advanceUntilIdle()
        f.edit.load(10)
        advanceUntilIdle()
        assertTrue(f.edit.state.value.canSubmit)
        assertEquals("USD", f.edit.state.value.currencyCode)
        f.edit.setNote("receipt")
        f.edit.submit()
        advanceUntilIdle()
        assertEquals(entry.copy(note = "receipt"), f.use.entries.stored.single())
    }

    @Test fun aCanceledDebtLookupCannotPutItsLateFailureOnANewerEntry() = runTest {
        val first = Transaction.Borrow(10, 1, 7, DebtFormTestFixture.usd("50"), day)
        val newer = Transaction.Expense(11, 1, DebtFormTestFixture.usd("20"), 6, date = day)
        val f = DebtFormTestFixture(initial = listOf(first, newer))
        val late = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        f.debts.findFor = { id ->
            withContext(NonCancellable) {
                started.complete(Unit)
                late.await()
                throw WalletException.DebtNotFound(id)
            }
        }
        advanceUntilIdle()
        try {
            f.edit.load(first.id)
            advanceUntilIdle()
            assertTrue(started.isCompleted)
            f.edit.load(newer.id)
            advanceUntilIdle()
            assertEquals(newer.id, f.edit.state.value.loaded!!.id)
            assertNull(f.edit.state.value.error)
        } finally {
            late.complete(Unit)
            advanceUntilIdle()
        }
        assertEquals(newer.id, f.edit.state.value.loaded!!.id)
        assertNull(f.edit.state.value.error)
    }

    private val entries: List<Transaction> = listOf(
        Transaction.Borrow(10, 1, 7, DebtFormTestFixture.usd("50"), day),
        Transaction.Lend(10, 2, 8, DebtFormTestFixture.eur("50"), day),
        Transaction.OwedBefore(10, 7, DebtSide.I_OWE, DebtFormTestFixture.usd("50"), day),
        Transaction.CreditPurchase(10, 7, DebtFormTestFixture.usd("50"), 6, date = day, rateToBase = BigDecimal("13000")),
        Transaction.EarnedUnpaid(10, 8, DebtFormTestFixture.eur("50"), 5, date = day, rateToBase = BigDecimal("14000")))
}
