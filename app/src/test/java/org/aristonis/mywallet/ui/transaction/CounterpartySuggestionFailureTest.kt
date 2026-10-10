package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CounterpartySuggestionFailureTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun addFormClearsSuggestionsQuietlyWhenTheLookupFails() = runTest {
        val f = DebtFormTestFixture()
        advanceUntilIdle()
        f.debts.suggestionsFor = { throw IllegalStateException("lookup failed") }
        f.add.selectType(TransactionType.BORROW)
        f.add.setCounterparty("Sa")
        advanceUntilIdle()
        assertNull(f.add.state.value.error)
        assertEquals(emptyList<String>(), f.add.state.value.counterpartySuggestions)
        assertEquals("Sa", f.add.state.value.counterparty)
    }

    @Test fun editFormClearsSuggestionsQuietlyWhenTheLookupFails() = runTest {
        val entry = Transaction.Borrow(10, 1, 7, DebtFormTestFixture.usd("50"), DebtFormTestFixture.day)
        val f = DebtFormTestFixture(initial = listOf(entry))
        advanceUntilIdle()
        f.edit.load(entry.id)
        advanceUntilIdle()
        f.debts.suggestionsFor = { throw IllegalStateException("lookup failed") }
        f.edit.setCounterparty("Sa")
        advanceUntilIdle()
        assertNull(f.edit.state.value.error)
        assertEquals(emptyList<String>(), f.edit.state.value.counterpartySuggestions)
        assertEquals("Sa", f.edit.state.value.counterparty)
    }
}
