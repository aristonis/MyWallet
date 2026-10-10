package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddDebtKindsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun eachNewKindSavesItsDebtReferenceThroughTheRealSaveEntry() = runTest {
        val types = listOf(TransactionType.BORROW, TransactionType.LEND, TransactionType.OWED_BEFORE,
            TransactionType.CREDIT_PURCHASE, TransactionType.EARNED_UNPAID)
        types.forEach { type ->
            val f = DebtFormTestFixture()
            advanceUntilIdle()
            f.add.selectType(type)
            f.add.setCounterparty("Mira")
            f.add.selectCurrency("EUR")
            f.add.selectDebtSide(DebtSide.OWED_TO_ME)
            f.add.setAmount("50")
            if (type == TransactionType.CREDIT_PURCHASE) f.add.selectCategory(6)
            if (type == TransactionType.EARNED_UNPAID) f.add.selectCategory(5)
            assertTrue(type.toString(), f.add.state.value.canSubmit)
            f.add.submit()
            advanceUntilIdle()
            assertNull(type.toString(), f.add.state.value.error)
            assertNotNull(type.toString(), f.add.state.value.savedOn)
            val entry = f.use.entries.stored.single()
            val debt = f.debts.observeAll().first()
                .single { it.counterparty == "Mira" }
            assertEquals(type.toString(), expectedSide(type), debt.side)
            assertEquals(type.toString(), if (type in accountKinds) "USD" else "EUR", debt.currencyCode)
            assertEquals(type.toString(), type, EntryFormRules.forEntry(entry)!!.type)
        }
    }

    @Test fun debtOnlyKindsSaveWithNoAccountsAvailable() = runTest {
        listOf(TransactionType.OWED_BEFORE, TransactionType.CREDIT_PURCHASE, TransactionType.EARNED_UNPAID).forEach { type ->
            val f = DebtFormTestFixture(accounts = emptyList())
            advanceUntilIdle()
            f.add.selectType(type)
            f.add.setCounterparty("Mira")
            f.add.selectCurrency("EUR")
            f.add.selectDebtSide(DebtSide.I_OWE)
            f.add.setAmount("20")
            if (type == TransactionType.CREDIT_PURCHASE) f.add.selectCategory(6)
            if (type == TransactionType.EARNED_UNPAID) f.add.selectCategory(5)
            f.add.submit()
            advanceUntilIdle()
            assertNull(type.toString(), f.add.state.value.error)
            assertEquals(type.toString(), 1, f.use.entries.stored.size)
        }
    }

    @Test fun borrowingAndLendingShowNoTrackingRateOrBaseEquivalent() = runTest {
        listOf(TransactionType.BORROW, TransactionType.LEND, TransactionType.OWED_BEFORE).forEach { type ->
            val f = DebtFormTestFixture()
            advanceUntilIdle()
            f.add.selectType(type)
            f.add.selectCurrency("EUR")
            f.add.setAmount("50")
            assertTrue(type.toString(), f.add.state.value.rateFields.isEmpty())
            assertNull(type.toString(), f.add.state.value.baseEquivalent)
            assertFalse(type.toString(), f.add.state.value.rateRequired)
        }
    }

    @Test fun anUnpaidCategoryEntryQuotesItsExplicitCurrencyInsteadOfAStaleAccount() = runTest {
        val f = DebtFormTestFixture()
        advanceUntilIdle()
        f.add.selectType(TransactionType.CREDIT_PURCHASE)
        f.add.selectAccount(99)
        f.add.selectCurrency("EUR")
        f.add.setAmount("50")
        assertEquals("EUR", f.add.state.value.rateFields.single().currencyCode)
        assertNotNull(f.add.state.value.baseEquivalent)
    }

    @Test fun borrowingOffersOnlyActiveAccountsAndRefusesAnUnavailableOne() = runTest {
        val f = DebtFormTestFixture()
        advanceUntilIdle()
        f.add.selectType(TransactionType.BORROW)
        f.add.setCounterparty("Ali")
        f.add.setAmount("50")
        assertEquals(listOf(1L, 2L), f.add.state.value.accounts.map { it.id })
        f.add.selectAccount(3)
        f.add.submit()
        advanceUntilIdle()
        assertEquals(UiMessage.AccountRequired, f.add.state.value.error)
        assertTrue(f.use.entries.stored.isEmpty())
    }

    @Test fun aMissingAccountSubmissionProducesANamedRefusal() = runTest {
        val f = DebtFormTestFixture(accounts = emptyList())
        advanceUntilIdle()
        f.add.selectType(TransactionType.BORROW)
        f.add.setCounterparty("Ali")
        f.add.setAmount("50")
        f.add.submit()
        advanceUntilIdle()
        assertEquals(UiMessage.AccountRequired, f.add.state.value.error)
        assertTrue(f.use.entries.stored.isEmpty())
    }

    @Test fun aLateSuggestionResultCannotReplaceResultsForTheLatestPrefix() = runTest {
        val f = DebtFormTestFixture()
        advanceUntilIdle()
        val oldResult = CompletableDeferred<List<String>>()
        f.debts.suggestionsFor = { prefix ->
            if (prefix == "A") withContext(NonCancellable) { oldResult.await() } else listOf("Sara")
        }
        f.add.setCounterparty("A")
        advanceUntilIdle()
        f.add.setCounterparty("Sa")
        advanceUntilIdle()
        try {
            assertEquals(listOf("Sara"), f.add.state.value.counterpartySuggestions)
        } finally {
            oldResult.complete(listOf("Ali"))
            advanceUntilIdle()
        }
        assertEquals("Sa", f.add.state.value.counterparty)
        assertEquals(listOf("Sara"), f.add.state.value.counterpartySuggestions)
    }

    @Test fun choosingASuggestionInvalidatesALatePrefixResponse() = runTest {
        val f = DebtFormTestFixture()
        advanceUntilIdle()
        val late = CompletableDeferred<List<String>>()
        f.debts.suggestionsFor = { withContext(NonCancellable) { late.await() } }
        f.add.setCounterparty("Sa")
        advanceUntilIdle()
        f.add.selectCounterpartySuggestion("Sara")
        assertEquals("Sara", f.add.state.value.counterparty)
        assertTrue(f.add.state.value.counterpartySuggestions.isEmpty())
        late.complete(listOf("Sarah"))
        advanceUntilIdle()
        assertEquals("Sara", f.add.state.value.counterparty)
        assertTrue(f.add.state.value.counterpartySuggestions.isEmpty())
    }

    private val accountKinds = listOf(TransactionType.BORROW, TransactionType.LEND)
    private fun expectedSide(type: TransactionType) = when (type) {
        TransactionType.LEND, TransactionType.EARNED_UNPAID, TransactionType.OWED_BEFORE -> DebtSide.OWED_TO_ME
        else -> DebtSide.I_OWE
    }
}
