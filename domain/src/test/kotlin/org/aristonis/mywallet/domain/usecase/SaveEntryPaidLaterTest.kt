package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.ali
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.usd
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.repayment
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.InjectedFault
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.*
import org.junit.Test

class SaveEntryPaidLaterTest {
    @Test fun feeWithoutAnyPaidNowIsTypedAndCreatesNoFallbackOrDebt() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val categories = rig.categoryRepository.stored
        val failure = runCatching {
            rig.saveEntry(purchase().copy(owed = usd("1000"), fee = usd("3")), emptyMap(), shop)
        }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.PaidLaterFeeWithoutPaidNow)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertTrue(rig.debts.stored.isEmpty())
        assertEquals(categories, rig.categoryRepository.stored)
    }

    @Test fun exactPurchaseSplitsCashCategoryAndDebtAndSharesNormalizedName() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val saved = rig.saveEntry(purchase(), emptyMap(), shop)
        rig.saveEntry(purchase(), emptyMap(), shop.copy(counterparty = " SHOP A "))
        val stored = rig.entries.findById(saved.id) as Transaction.Expense
        assertEquals(usd("1000"), stored.amount)
        assertEquals(usd("700"), stored.owed)
        assertEquals(rig.debts.stored.single().id, stored.owedDebtId)
        val parts = rig.entries.rows.value.first()
        assertEquals(usd("-300"), parts.legs.single { it.role == LegRole.ACCOUNT }.amount)
        assertEquals(usd("300"), parts.legs.single { it.role == LegRole.MAIN }.amount)
        assertEquals(usd("700"), parts.legs.single { it.role == LegRole.PAID_LATER }.amount)
        assertEquals(usd("1400"), DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(stored.owedDebtId!!))).getValue(stored.owedDebtId!!).remaining)
    }

    @Test fun blankNameRefusesBeforeAnyWrite() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val failure = runCatching { rig.saveEntry(purchase(), emptyMap(), shop.copy(counterparty = " ")) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.NameBlank)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertTrue(rig.debts.stored.isEmpty())
    }

    @Test fun existingDebtWithWrongSideOrCurrencyRefusesAtomically() = runTest {
        val incompatible = listOf(ali.copy(side = DebtSide.OWED_TO_ME), ali.copy(currencyCode = "SYP"))
        incompatible.forEach { debt ->
            val rig = SaveEntryRig(initialDebts = listOf(debt), atomic = true)
            val failure = runCatching { rig.saveEntry(purchase().copy(owedDebtId = debt.id), emptyMap()) }.exceptionOrNull()
            if (debt.side != DebtSide.I_OWE) assertTrue("was $failure", failure is WalletException.DebtSideMismatch)
            else assertTrue("was $failure", failure is WalletException.CurrencyMismatch)
            assertTrue(rig.entries.rows.value.isEmpty())
            assertEquals(listOf(debt), rig.debts.stored)
        }
    }

    @Test fun fullyDeferredPurchaseLeavesCashAtZeroAndOwesFullPrice() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val saved = rig.saveEntry(purchase().copy(owed = usd("1000")), emptyMap(), shop)
        val legs = rig.entries.rows.value.single().legs
        assertEquals(usd("0"), legs.single { it.role == LegRole.ACCOUNT }.amount)
        assertEquals(usd("0"), legs.single { it.role == LegRole.MAIN }.amount)
        assertEquals(usd("1000"), (rig.entries.findById(saved.id) as Transaction.Expense).owed)
    }

    @Test fun changingPaidNowRecomputesDebtAndRefusesRepaidPrincipalAtomically() = runTest {
        val purchase = purchase().copy(id = 1, owedDebtId = 7)
        val rig = SaveEntryRig(initialDebts = listOf(ali.copy(counterparty = "Shop A")), initialEntries = listOf(purchase), atomic = true)
        rig.saveEntry(purchase.copy(owed = usd("500")), emptyMap())
        assertEquals(usd("500"), (rig.entries.findById(1) as Transaction.Expense).owed)
        rig.saveEntry(purchase, emptyMap())
        rig.saveEntry(repayment(amount = "600"), emptyMap())
        val before = rig.entries.rows.value
        val failure = runCatching { rig.saveEntry(purchase.copy(owed = usd("200")), emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals("Shop A", (failure as WalletException.DebtBelowZero).counterparty)
        assertEquals(usd("400"), failure.amount)
        assertEquals(before, rig.entries.rows.value)
    }

    @Test fun failedWriteRollsBackNewPersonAndFeeFallback() = runTest {
        val rig = SaveEntryRig(atomic = true, faultAfterWrite = true)
        val categories = rig.categoryRepository.stored
        val failure = runCatching { rig.saveEntry(purchase().copy(fee = usd("3")), emptyMap(), shop) }.exceptionOrNull()
        assertTrue("was $failure", failure is InjectedFault)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertTrue(rig.debts.stored.isEmpty())
        assertEquals(categories, rig.categoryRepository.stored)
    }

    @Test fun typedDueDateIsAppliedAfterReopeningClearsStaleDetails() = runTest {
        val old = ali.copy(counterparty = "Shop A", dueDate = EntryWorld.day, note = "old")
        val rig = SaveEntryRig(initialDebts = listOf(old), atomic = true)
        val due = EntryWorld.day.plusMonths(1)
        val saved = rig.saveEntry(purchase().copy(owedDebtId = old.id), emptyMap(), dueDate = due)
        assertEquals(due, rig.debts.findById(old.id)!!.dueDate)
        assertNull(rig.debts.findById(old.id)!!.note)
        val bundle = rig.deleteEntry(saved.id)
        assertEquals(due, rig.debts.findById(old.id)!!.dueDate)
        rig.restoreEntries(bundle)
        assertEquals(due, rig.debts.findById(old.id)!!.dueDate)
    }

    @Test fun typedDueDateReplacesRunningDebtDateAndEmptyInputKeepsIt() = runTest {
        val opening = purchase().copy(id = 1, owedDebtId = ali.id)
        val old = ali.copy(counterparty = "Shop A", dueDate = EntryWorld.day, note = "keep")
        val rig = SaveEntryRig(initialEntries = listOf(opening), initialDebts = listOf(old), atomic = true)
        val due = EntryWorld.day.plusMonths(2)
        rig.saveEntry(purchase(), emptyMap(), shop, dueDate = due)
        assertEquals(due, rig.debts.findById(old.id)!!.dueDate)
        assertEquals("keep", rig.debts.findById(old.id)!!.note)
        rig.saveEntry(purchase(), emptyMap(), shop, dueDate = null)
        assertEquals(due, rig.debts.findById(old.id)!!.dueDate)
    }

    @Test fun failedSaveRollsBackTypedDueDateAndReopenedDetails() = runTest {
        val old = ali.copy(counterparty = "Shop A", dueDate = EntryWorld.day, note = "keep")
        val rig = SaveEntryRig(initialDebts = listOf(old), atomic = true, faultAfterFill = true)
        val failure = runCatching {
            rig.saveEntry(purchase().copy(owedDebtId = old.id), mapOf("SYP" to java.math.BigDecimal("0.001")), dueDate = EntryWorld.day.plusMonths(1))
        }.exceptionOrNull()
        assertTrue("was $failure", failure is InjectedFault)
        assertEquals(old, rig.debts.findById(old.id))
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    companion object {
        val shop = DebtRef("Shop A", DebtSide.I_OWE, "USD")
        fun purchase() = EntryWorld.expense(amount = "1000").copy(owed = usd("700"))
    }
}
