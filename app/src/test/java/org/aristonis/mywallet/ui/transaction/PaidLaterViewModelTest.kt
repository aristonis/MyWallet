package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.aristonis.mywallet.domain.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaidLaterViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun addSavesPaidNowAndNameAsOneExpenseWithDebt() = runTest {
        val f = DebtFormTestFixture()
        advanceUntilIdle()
        f.add.selectCategory(6); f.add.setAmount("1000")
        f.add.setPaidLaterEnabled(true); f.add.setPaidNow("300"); f.add.setPaidLaterCounterparty("Shop A")
        f.add.submit(); advanceUntilIdle()
        val saved = f.use.entries.observeAll().first().single() as Transaction.Expense
        assertEquals(DebtFormTestFixture.usd("700"), saved.owed)
        assertNotNull(saved.owedDebtId)
        assertEquals("Shop A", f.debts.findById(saved.owedDebtId!!)!!.counterparty)
    }
    @Test fun switchingTypeDiscardsPaidLaterBeforeAnotherExpense() = runTest {
        val f = DebtFormTestFixture(); advanceUntilIdle()
        f.add.setPaidLaterEnabled(true); f.add.setPaidNow("300"); f.add.setPaidLaterCounterparty("Shop A")
        f.add.selectType(TransactionType.INCOME); f.add.selectType(TransactionType.EXPENSE)
        assertFalse(f.add.state.value.paidLater.enabled)
        assertEquals("", f.add.state.value.paidLater.counterparty)
    }
    @Test fun editKeepsPaidNowWhenAmountChangesAndSavesRecomputedOwed() = runTest {
        val original = Transaction.Expense(id = 50, accountId = 1, amount = DebtFormTestFixture.usd("1000"),
            categoryId = 6, date = DebtFormTestFixture.day, owed = DebtFormTestFixture.usd("700"), owedDebtId = 7)
        val f = DebtFormTestFixture(listOf(original)); advanceUntilIdle()
        f.edit.load(50); advanceUntilIdle()
        assertEquals("300", f.edit.state.value.paidLater.paidNowInput)
        f.edit.setAmount("1200")
        assertEquals(DebtFormTestFixture.usd("900"), f.edit.state.value.paidLater.owed)
        f.edit.submit(); advanceUntilIdle()
        assertEquals(DebtFormTestFixture.usd("900"), (f.use.entries.findById(50) as Transaction.Expense).owed)
    }
    @Test fun feePercentageUsesPaidNowAndPreviewIncludesOnlyCurrentPayment() = runTest {
        val f = DebtFormTestFixture(); advanceUntilIdle()
        f.add.selectCategory(6); f.add.setAmount("1000")
        f.add.setPaidLaterEnabled(true); f.add.setPaidNow("300"); f.add.setPaidLaterCounterparty("Shop A")
        f.add.selectFeeMode(FeeMode.PERCENT); f.add.setFeeValue("1")
        assertEquals(DebtFormTestFixture.usd("3.00"), f.add.state.value.fee.amount)
        assertEquals(DebtFormTestFixture.usd("303.00"), f.add.state.value.fee.accountMovement)
        f.add.setPaidNow("")
        assertNull(f.add.state.value.fee.amount)
        assertEquals(DebtFormTestFixture.usd("0"), f.add.state.value.fee.accountMovement)
    }
    @Test fun selectedDebtAndDueDateReachSaveAndWrongCurrencySelectionIsIgnored() = runTest {
        val f = DebtFormTestFixture(); advanceUntilIdle()
        f.add.selectCategory(6); f.add.setAmount("1000"); f.add.setPaidLaterEnabled(true)
        f.add.selectPaidLaterDebt(8)
        assertNull(f.add.state.value.paidLater.debtId)
        f.add.selectPaidLaterDebt(7); f.add.setPaidNow("300")
        val due = DebtFormTestFixture.day.plusDays(30)
        f.add.setPaidLaterDueDate(due); f.add.submit(); advanceUntilIdle()
        val saved = f.use.entries.observeAll().first().single() as Transaction.Expense
        assertEquals(7L, saved.owedDebtId)
        assertEquals(due, f.debts.findById(7)!!.dueDate)
    }
}
