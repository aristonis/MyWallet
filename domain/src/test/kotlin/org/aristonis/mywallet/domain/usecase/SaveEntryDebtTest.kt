package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.ali
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.sara
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.usd
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.borrow
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.lend
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.repayment
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.InjectedFault
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class SaveEntryDebtTest {
    @Test fun borrowingTwiceFromTheSameNameUsesOneRunningDebt() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val first = rig.saveEntry(borrow(debtId = 0), emptyMap(), DebtRef(" Ali ", DebtSide.I_OWE, "USD"))
        val second = rig.saveEntry(borrow(debtId = 0), emptyMap(), DebtRef("ALI", DebtSide.I_OWE, "USD"))
        val debtId = rig.debts.stored.single().id
        assertEquals(debtId, (rig.entries.findById(first.id) as Transaction.Borrow).debtId)
        assertEquals(debtId, (rig.entries.findById(second.id) as Transaction.Borrow).debtId)
        assertEquals(usd("200"), DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(debtId))).getValue(debtId).remaining)
    }

    @Test fun aFailedSaveLeavesNoNewDebtRow() = runTest {
        val rig = SaveEntryRig(atomic = true, faultAfterWrite = true)
        val failure = runCatching {
            rig.saveEntry(borrow(debtId = 0), emptyMap(), DebtRef("Ali", DebtSide.I_OWE, "USD"))
        }.exceptionOrNull()
        assertTrue("was $failure", failure is InjectedFault)
        assertTrue(rig.debts.stored.isEmpty())
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun anInvalidAccountLeavesNoNewDebtRow() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val failure = runCatching {
            rig.saveEntry(borrow(debtId = 0).copy(accountId = 99), emptyMap(), DebtRef("Ali", DebtSide.I_OWE, "USD"))
        }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.AccountNotFound)
        assertTrue(rig.debts.stored.isEmpty())
    }

    @Test fun aBlankCounterpartyIsRefusedWithoutWriting() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val failure = runCatching { rig.saveEntry(borrow(), emptyMap(), DebtRef("  ", DebtSide.I_OWE, "USD")) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.NameBlank)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun aMissingDebtIsATypedRefusal() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val failure = runCatching { rig.saveEntry(borrow(), emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtNotFound)
        assertEquals(7L, (failure as WalletException.DebtNotFound).id)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun aBorrowCannotUseAnOwedToMeDebt() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(sara), atomic = true)
        val failure = runCatching { rig.saveEntry(borrow(debtId = 8), emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtSideMismatch)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun aRepaymentSideMustMatchItsDebtRow() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(ali), atomic = true)
        val failure = runCatching { rig.saveEntry(repayment(side = DebtSide.OWED_TO_ME), emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtSideMismatch)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun aWriteOffSideMustMatchItsDebtRow() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(ali), atomic = true)
        val entry = Transaction.WriteOff(side = DebtSide.OWED_TO_ME, debtId = 7, amount = usd("10"), categoryId = 6, date = EntryWorld.day)
        val failure = runCatching { rig.saveEntry(entry, emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtSideMismatch)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun anOwedBeforeSideMustMatchItsDebtRow() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(ali), atomic = true)
        val entry = Transaction.OwedBefore(debtId = 7, side = DebtSide.OWED_TO_ME, amount = usd("10"), date = EntryWorld.day)
        val failure = runCatching { rig.saveEntry(entry, emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtSideMismatch)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun aDebtLegMustUseItsDebtCurrency() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(ali.copy(currencyCode = "SYP")), atomic = true)
        val failure = runCatching { rig.saveEntry(borrow(), emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.CurrencyMismatch)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun aNamedDebtReferenceMustFitTheEntrySide() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val failure = runCatching {
            rig.saveEntry(borrow(debtId = 0), emptyMap(), DebtRef("Ali", DebtSide.OWED_TO_ME, "USD"))
        }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtSideMismatch)
        assertTrue(rig.debts.stored.isEmpty())
    }

    @Test fun repayingMoreThanRemainsRollsBackTheEntry() = runTest {
        val loan = borrow(id = 1)
        val rig = SaveEntryRig(initialDebts = listOf(ali), initialEntries = listOf(loan), atomic = true)
        val failure = runCatching { rig.saveEntry(repayment(amount = "101"), emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals("Ali", (failure as WalletException.DebtBelowZero).counterparty)
        assertEquals(usd("1"), failure.amount)
        assertEquals(loan, rig.entries.findById(1))
        assertEquals(1, rig.entries.rows.value.size)
    }

    @Test fun movingARepaidLoanToAnotherPersonGuardsTheOldDebtToo() = runTest {
        val loan = borrow(id = 1)
        val paid = repayment(id = 2, amount = "100")
        val other = ali.copy(id = 9, counterparty = "Omar")
        val rig = SaveEntryRig(initialDebts = listOf(ali, other), initialEntries = listOf(loan, paid), atomic = true)
        val failure = runCatching { rig.saveEntry(loan.copy(debtId = 9), emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(loan, rig.entries.findById(1))
        assertEquals(usd("0"), DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(7))).getValue(7).remaining)
    }

    @Test fun loweringARepaidLoanIsRefused() = runTest {
        val loan = borrow(id = 1)
        val rig = SaveEntryRig(initialDebts = listOf(ali), initialEntries = listOf(loan, repayment(id = 2)), atomic = true)
        val failure = runCatching { rig.saveEntry(loan.copy(amount = usd("40")), emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(loan, rig.entries.findById(1))
    }

    @Test fun lendingAgainToASettledPersonClearsOldDetails() = runTest {
        val detailed = sara.copy(dueDate = EntryWorld.day, note = "old loan")
        val rig = SaveEntryRig(initialDebts = listOf(detailed), initialEntries = listOf(lend(id = 1), repayment(id = 2, debtId = 8, side = DebtSide.OWED_TO_ME, amount = "100")), atomic = true)
        rig.saveEntry(lend(), emptyMap())
        assertNull(rig.debts.findById(8)!!.dueDate)
        assertNull(rig.debts.findById(8)!!.note)
    }

    @Test fun anAmountOnlyEditOfAnOpeningDebtKeepsItsDetails() = runTest {
        val detailed = sara.copy(dueDate = EntryWorld.day, note = "keep")
        val loan = lend(id = 1)
        val rig = SaveEntryRig(initialDebts = listOf(detailed), initialEntries = listOf(loan, repayment(id = 2, debtId = 8, side = DebtSide.OWED_TO_ME, amount = "100")), atomic = true)
        rig.saveEntry(loan.copy(amount = usd("150")), emptyMap())
        assertEquals(detailed, rig.debts.findById(8))
    }

    @Test fun movingALoanToAnEmptyDebtClearsOnlyTheNewDebtDetails() = runTest {
        val old = sara.copy(dueDate = EntryWorld.day, note = "old")
        val other = sara.copy(id = 9, counterparty = "Mira", dueDate = EntryWorld.day, note = "stale")
        val loan = lend(id = 1)
        val rig = SaveEntryRig(initialDebts = listOf(old, other), initialEntries = listOf(loan), atomic = true)
        rig.saveEntry(loan.copy(debtId = 9), emptyMap())
        assertEquals(old, rig.debts.findById(8))
        assertEquals(other.copy(dueDate = null, note = null), rig.debts.findById(9))
    }

    @Test fun aFailedReopenRestoresOldDebtDetails() = runTest {
        val detailed = sara.copy(dueDate = EntryWorld.day, note = "old loan")
        val rig = SaveEntryRig(initialDebts = listOf(detailed), faultAfterWrite = true, atomic = true)
        val failure = runCatching { rig.saveEntry(lend(), emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is InjectedFault)
        assertEquals(detailed, rig.debts.findById(8))
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun aNoteOnlyEditOfAPaidInFullCrossCurrencyRepaymentKeepsBothAmounts() = runTest {
        val debt = ali.copy(currencyCode = "SYP")
        val opening = Transaction.OwedBefore(id = 1, debtId = 7, side = DebtSide.I_OWE, amount = Money.of("1000050", "SYP"), date = EntryWorld.day)
        val paid = Transaction.Repayment(id = 2, side = DebtSide.I_OWE, accountId = 1, debtId = 7, accountAmount = usd("76.93"), principal = Money.of("1000050", "SYP"), pairRate = BigDecimal("13000"), date = EntryWorld.day)
        val rig = SaveEntryRig(initialDebts = listOf(debt), initialEntries = listOf(opening, paid), atomic = true)
        rig.saveEntry(paid.copy(note = "receipt"), emptyMap())
        assertEquals(paid.copy(note = "receipt"), rig.entries.findById(2))
        assertEquals(Money.zero("SYP"), DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(7))).getValue(7).remaining)
    }

    @Test fun repaymentInterestFallsBackBeforeItsLegsAreBuilt() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(ali), initialEntries = listOf(borrow(id = 1)), atomic = true)
        val entry = repayment().copy(accountAmount = usd("55"), interest = usd("5"))
        val saved = rig.saveEntry(entry, emptyMap())
        val stored = rig.entries.findById(saved.id) as Transaction.Repayment
        val bucket = rig.categoryRepository.stored.single { it.systemKey != null }
        assertEquals(CategoryKind.EXPENSE, bucket.kind)
        assertEquals(bucket.id, stored.interestCategoryId)
        assertEquals(BigDecimal.ONE, stored.interestRateToBase)
    }

    @Test fun anExplicitInterestCategoryWinsOverTheRoleBinding() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(ali), initialEntries = listOf(borrow(id = 1)), roleBindings = mapOf(CategoryRole.INTEREST_OUT to 99), atomic = true)
        val saved = rig.saveEntry(repayment().copy(accountAmount = usd("55"), interest = usd("5"), interestCategoryId = 6), emptyMap())
        assertEquals(6L, (rig.entries.findById(saved.id) as Transaction.Repayment).interestCategoryId)
        assertEquals(99L, rig.roles.stored[CategoryRole.INTEREST_OUT])
    }

    @Test fun aRefusedInterestSaveRollsBackItsFallbackBucket() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(ali), initialEntries = listOf(borrow(id = 1)), atomic = true)
        val categoriesBefore = rig.categoryRepository.stored
        val failure = runCatching { rig.saveEntry(repayment(amount = "101").copy(accountAmount = usd("106"), interest = usd("5")), emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(categoriesBefore, rig.categoryRepository.stored)
        assertEquals(1, rig.entries.rows.value.size)
    }

    @Test fun aWriteOffWithNoPickedCategoryUsesItsRoleFallback() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(sara), initialEntries = listOf(lend(id = 1)), atomic = true)
        val entry = Transaction.WriteOff(side = DebtSide.OWED_TO_ME, debtId = 8, amount = usd("100"), categoryId = 0, date = EntryWorld.day)
        val saved = rig.saveEntry(entry, emptyMap())
        val stored = rig.entries.findById(saved.id) as Transaction.WriteOff
        assertEquals(CategoryKind.EXPENSE, rig.categoryRepository.findById(stored.categoryId)!!.kind)
        assertEquals(usd("0"), DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(8))).getValue(8).remaining)
    }
    @Test fun aCreditPurchaseGrowsTheDebtAndExpenseWithoutMovingAccountMoney() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(ali), atomic = true)
        val entry = Transaction.CreditPurchase(debtId = 7, amount = usd("20"), categoryId = 6, date = EntryWorld.day)
        val saved = rig.saveEntry(entry, emptyMap())
        val parts = rig.entries.rows.value.single()
        assertFalse(parts.legs.any { it.target is org.aristonis.mywallet.domain.ledger.LegTarget.Account })
        assertEquals(usd("20"), DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(7))).getValue(7).remaining)
        val stored = rig.entries.findById(saved.id) as Transaction.CreditPurchase
        assertEquals(6L, stored.categoryId)
        assertEquals(usd("20"), stored.amount)
        assertEquals(BigDecimal.ONE, stored.rateToBase)
    }

    @Test fun earnedUnpaidGrowsTheDebtAndIncomeWithoutMovingAccountMoney() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(sara), atomic = true)
        val entry = Transaction.EarnedUnpaid(debtId = 8, amount = usd("80"), categoryId = 5, date = EntryWorld.day)
        val saved = rig.saveEntry(entry, emptyMap())
        assertFalse(rig.entries.rows.value.single().legs.any { it.target is org.aristonis.mywallet.domain.ledger.LegTarget.Account })
        assertEquals(usd("80"), DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(8))).getValue(8).remaining)
        val stored = rig.entries.findById(saved.id) as Transaction.EarnedUnpaid
        assertEquals(5L, stored.categoryId)
        assertEquals(usd("80"), stored.amount)
        assertEquals(BigDecimal.ONE, stored.rateToBase)
    }

    @Test fun normalizedNamesShareOneDebtButOppositeSidesRemainSeparate() = runTest {
        val rig = SaveEntryRig(atomic = true)
        rig.saveEntry(borrow(debtId = 0), emptyMap(), DebtRef(" Ali ", DebtSide.I_OWE, "USD"))
        rig.saveEntry(borrow(debtId = 0, amount = "50"), emptyMap(), DebtRef("ALI", DebtSide.I_OWE, "USD"))
        rig.saveEntry(borrow(debtId = 0, amount = "50"), emptyMap(), DebtRef("ali", DebtSide.I_OWE, "USD"))
        rig.saveEntry(lend(debtId = 0, amount = "30"), emptyMap(), DebtRef("Ali", DebtSide.OWED_TO_ME, "USD"))
        assertEquals(2, rig.debts.stored.size)
        val sums = DebtRemaining.byDebt(rig.ledger.debtLegs(rig.debts.stored.map { it.id }.toSet()))
        val owed = rig.debts.stored.single { it.side == DebtSide.I_OWE }
        val receivable = rig.debts.stored.single { it.side == DebtSide.OWED_TO_ME }
        assertEquals(usd("200"), sums.getValue(owed.id).remaining)
        assertEquals(usd("30"), sums.getValue(receivable.id).remaining)
    }

    @Test fun namedDebtResolutionRunsInsideTheWriteTransaction() = runTest {
        val rig = SaveEntryRig(atomic = true)
        var calls = 0
        rig.debts.beforeGetOrCreate = {
            assertTrue((rig.runner as org.aristonis.mywallet.domain.usecase.fake.RollbackOnFailureRunner).isRunning)
            calls++
        }
        rig.saveEntry(borrow(debtId = 0), emptyMap(), DebtRef("Ali", DebtSide.I_OWE, "USD"))
        assertEquals(1, calls)
    }

    @Test fun anOwedBeforeDebtInAnUnknownCurrencyIsRefusedWithoutCreatingARow() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val entry = Transaction.OwedBefore(debtId = 0, side = DebtSide.I_OWE,
            amount = Money.of("100", "XYZ"), date = EntryWorld.day)
        val categoriesBefore = rig.categoryRepository.stored
        val failure = runCatching {
            rig.saveEntry(entry, mapOf("SYP" to BigDecimal("0.0002")), DebtRef("Ali", DebtSide.I_OWE, "XYZ"))
        }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.CurrencyNotFound)
        assertEquals("XYZ", (failure as WalletException.CurrencyNotFound).code)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertTrue(rig.debts.stored.isEmpty())
        assertEquals(categoriesBefore, rig.categoryRepository.stored)
        assertNull(rig.rates.findByCode("SYP"))
    }

    @Test fun aDebtOnlyWriteOffInAnUnknownCurrencyRollsBackItsFallbackCategoryAndRates() = runTest {
        val debt = sara.copy(currencyCode = "XYZ")
        val opening = Transaction.OwedBefore(id = 1, debtId = 8, side = DebtSide.OWED_TO_ME,
            amount = Money.of("100", "XYZ"), date = EntryWorld.day)
        val rig = SaveEntryRig(initialDebts = listOf(debt), initialEntries = listOf(opening), atomic = true)
        val entry = Transaction.WriteOff(side = DebtSide.OWED_TO_ME, debtId = 8,
            amount = Money.of("100", "XYZ"), categoryId = 0, date = EntryWorld.day)
        val categoriesBefore = rig.categoryRepository.stored
        val failure = runCatching { rig.saveEntry(entry, mapOf("SYP" to BigDecimal("0.0002"))) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.CurrencyNotFound)
        assertEquals("XYZ", (failure as WalletException.CurrencyNotFound).code)
        assertEquals(opening, rig.entries.findById(1))
        assertEquals(1, rig.entries.rows.value.size)
        assertEquals(listOf(debt), rig.debts.stored)
        assertEquals(categoriesBefore, rig.categoryRepository.stored)
        assertNull(rig.rates.findByCode("SYP"))
    }

    @Test fun aFaultDuringRateFillRollsBackTheReopenedDebtDetailsAndTheNewEntry() = runTest {
        val detailed = sara.copy(dueDate = EntryWorld.day, note = "old loan")
        val loan = lend(id = 1)
        val paid = repayment(id = 2, debtId = 8, side = DebtSide.OWED_TO_ME, amount = "100")
        val olderUnrated = EntryWorld.expense(id = 3, accountId = 2, amount = "5000", currency = "SYP")
        val rig = SaveEntryRig(initialDebts = listOf(detailed), initialEntries = listOf(loan, paid, olderUnrated),
            faultAfterFill = true, atomic = true)
        val failure = runCatching { rig.saveEntry(lend(), mapOf("SYP" to BigDecimal("0.0002"))) }.exceptionOrNull()
        assertTrue("was $failure", failure is InjectedFault)
        assertEquals(detailed, rig.debts.findById(8))
        assertEquals(3, rig.entries.rows.value.size)
        assertEquals(olderUnrated, rig.entries.findById(3))
        assertNull(rig.rates.findByCode("SYP"))
    }

}
