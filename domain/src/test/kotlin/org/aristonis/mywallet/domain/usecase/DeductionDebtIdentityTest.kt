package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An owed-later deduction is a debt to the line's named payee in the salary currency. An edit may carry
 * the debt id the line had before; that id is only kept while it still names that payee and currency.
 */
class DeductionDebtIdentityTest {
    private val eurAccount = EntryWorld.account(4, "EUR")

    @Test fun movingASalaryToAnotherCurrencyBooksTheTaxInANewDebtOfThatCurrency() = runTest {
        val rig = salaryRig(accounts = listOf(EntryWorld.usdAccount, eurAccount))
        val stored = saveTaxOnlySalary(rig)
        val usdTax = rig.debts.stored.single()
        val line = stored.deductions.single()
        val moved = stored.copy(
            accountId = eurAccount.id,
            amount = Money.of("1000", "EUR"),
            deductions = listOf(line.copy(valueCurrencyCode = "EUR", amount = Money.of("100", "EUR"))),
        )

        rig.saveEntry(moved, emptyMap())

        val reread = rig.entries.findById(stored.id) as Transaction.Income
        val eurTax = rig.debts.stored.single { it.currencyCode == "EUR" }
        assertEquals("Tax", eurTax.counterparty)
        assertEquals(DebtSide.I_OWE, eurTax.side)
        assertEquals(eurTax.id, reread.deductions.single().debtId)
        assertEquals(Money.of("100", "EUR"), remaining(rig, eurTax.id))
        assertEquals(usd("0"), remaining(rig, usdTax.id))
    }

    @Test fun swappingTheItemAtOnePositionBooksTheNewPayeeAndReleasesTheOldOne() = runTest {
        val rig = salaryRig()
        val stored = saveTaxOnlySalary(rig)
        val tax = rig.debts.stored.single()
        val swapped = stored.deductions.single().copy(itemId = 11, name = "Family", categoryId = 21)

        rig.saveEntry(stored.copy(deductions = listOf(swapped)), emptyMap())

        val family = rig.debts.stored.single { it.counterparty == "Family" }
        assertEquals(DebtSide.I_OWE, family.side)
        assertEquals(usd("100"), remaining(rig, family.id))
        assertEquals(usd("0"), remaining(rig, tax.id))
        val reread = rig.entries.findById(stored.id) as Transaction.Income
        assertEquals(family.id, reread.deductions.single().debtId)
    }

    @Test fun anUnchangedEditKeepsTheSameDebtAndCreatesNoRow() = runTest {
        val rig = salaryRig()
        val stored = saveTaxOnlySalary(rig)
        val debtsBefore = rig.debts.stored

        rig.saveEntry(stored.copy(note = "March"), emptyMap())

        val reread = rig.entries.findById(stored.id) as Transaction.Income
        assertEquals(debtsBefore, rig.debts.stored)
        assertEquals(stored.deductions.single().debtId, reread.deductions.single().debtId)
        assertEquals(usd("100"), remaining(rig, debtsBefore.single().id))
    }

    private suspend fun saveTaxOnlySalary(rig: SaveEntryRig): Transaction.Income {
        val entry = salary().copy(deductions = listOf(salaryLine(10, 0, "Tax", 20, "100")))
        val id = rig.saveEntry(entry, emptyMap()).id
        return rig.entries.findById(id) as Transaction.Income
    }

    private suspend fun remaining(rig: SaveEntryRig, id: Long): Money {
        val debt = rig.debts.stored.single { it.id == id }
        return DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(id)))[id]?.remaining ?: Money.zero(debt.currencyCode)
    }
}
