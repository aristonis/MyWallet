package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.day
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.opening
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.syp
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.usd
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class RecordRepaymentRefusalTest {
    @Test fun payingTheRestOfASettledDebtIsATypedRefusalAndWritesNothing() = runTest {
        val rig = RepaymentTestRig()
        rig.record(7, 1, null, day)
        val failure = runCatching { rig.record(7, 1, null, day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtHasNoRemaining)
        assertEquals("Ali", (failure as WalletException.DebtHasNoRemaining).counterparty)
        assertEquals(2, rig.store.entries.rows.value.size)
    }

    @Test fun payingTheRestOfADebtBelowZeroIsRefusedLikeAWriteOff() = runTest {
        val paid = Transaction.Repayment(id = 2, side = DebtSide.I_OWE, accountId = 1, debtId = 7,
            accountAmount = usd("100"), principal = usd("100"), date = day)
        val rig = RepaymentTestRig(initialEntries = listOf(opening(amount = "50"), paid))
        val failure = runCatching { rig.record(7, 1, null, day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals("Ali", (failure as WalletException.DebtBelowZero).counterparty)
        assertEquals(usd("50"), failure.amount)
        assertEquals(2, rig.store.entries.rows.value.size)
    }

    @Test fun accountPrincipalDebtPrincipalAndPairRateTogetherAreRefused() = runTest {
        val rig = RepaymentTestRig(debts = listOf(DebtEntryWorld.ali.copy(currencyCode = "SYP")),
            initialEntries = listOf(opening(amount = "1000050", currency = "SYP")))
        val failure = runCatching {
            rig.record(7, 1, syp("1000050"), day, accountPrincipal = usd("76.93"), pairRate = BigDecimal("13000"))
        }.exceptionOrNull()
        assertTrue("was $failure", failure is IllegalArgumentException)
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun anEditThatPassesNoInterestCategoryKeepsTheStoredOne() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(debtId = 8, side = DebtSide.OWED_TO_ME)))
        val saved = rig.record(8, 1, usd("400"), day, interest = usd("50"), interestCategoryId = 5)
        rig.record(8, 1, usd("400"), day, interest = usd("50"), note = "receipt", id = saved.id)
        val edited = rig.saved(saved.id)
        assertEquals(5L, edited.interestCategoryId)
        assertEquals("receipt", edited.note)
    }

    @Test fun aDebtReferenceOnAKindWithNoDebtLegIsRefused() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val failure = runCatching {
            rig.saveEntry(EntryWorld.expense(), emptyMap(), DebtRef("Ali", DebtSide.I_OWE, "USD"))
        }.exceptionOrNull()
        assertTrue("was $failure", failure is IllegalArgumentException)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertTrue(rig.debts.stored.isEmpty())
    }
}
