package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.day
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.opening
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.usd
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.InjectedFault
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class RecordRepaymentTest {
    @Test fun paying400Of5000Leaves4600AndAddsNoTrackingExpense() = runTest {
        val rig = RepaymentTestRig()
        val saved = rig.record(debtId = 7, accountId = 1, principal = usd("400"), date = day)
        assertEquals(usd("4600"), rig.remaining())
        assertEquals(usd("400"), rig.saved(saved.id).accountAmount)
        assertEquals(listOf(usd("-400")), rig.store.ledger.observeAccountLegs().first().map { it.amount })
        assertTrue(rig.store.ledger.observeCategoryLegs(DateRange.ALL_TIME).first().isEmpty())
    }

    @Test fun collecting400Of5000Leaves4600AndAddsNoTrackingIncome() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(debtId = 8, side = DebtSide.OWED_TO_ME)))
        val saved = rig.record(debtId = 8, accountId = 1, principal = usd("400"), date = day)
        assertEquals(usd("4600"), rig.remaining(8))
        assertEquals(DebtSide.OWED_TO_ME, rig.saved(saved.id).side)
        assertEquals(listOf(usd("400")), rig.store.ledger.observeAccountLegs().first().map { it.amount })
        assertTrue(rig.store.ledger.observeCategoryLegs(DateRange.ALL_TIME).first().isEmpty())
    }

    @Test fun payingAboveRemainingNamesTheAvailableAmountAndWritesNothing() = runTest {
        val rig = RepaymentTestRig()
        val failure = runCatching { rig.record(7, 1, usd("5001"), day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.RepaymentExceedsRemaining)
        assertEquals(usd("5000"), (failure as WalletException.RepaymentExceedsRemaining).remaining)
        assertEquals(1, rig.store.entries.rows.value.size)
        assertEquals(usd("5000"), rig.remaining())
    }

    @Test fun paying400PrincipalAnd50InterestMoves450AndTracksOnly50Expense() = runTest {
        val rig = RepaymentTestRig()
        val saved = rig.record(7, 1, usd("400"), day, interest = usd("50"))
        assertEquals(usd("450"), rig.saved(saved.id).accountAmount)
        assertEquals(usd("4600"), rig.remaining())
        val categoryLeg = rig.store.ledger.observeCategoryLegs(DateRange.ALL_TIME).first().single()
        assertEquals(usd("50"), categoryLeg.amount)
        assertEquals(BigDecimal.ONE, categoryLeg.rateToBase)
    }

    @Test fun collecting400PrincipalAnd50InterestMoves450AndTracksOnly50Income() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(debtId = 8, side = DebtSide.OWED_TO_ME)))
        val saved = rig.record(8, 1, usd("400"), day, interest = usd("50"), interestCategoryId = 5)
        assertEquals(usd("450"), rig.saved(saved.id).accountAmount)
        assertEquals(usd("4600"), rig.remaining(8))
        val categoryLeg = rig.store.ledger.observeCategoryLegs(DateRange.ALL_TIME).first().single()
        assertEquals(5L, categoryLeg.categoryId)
        assertEquals(usd("50"), categoryLeg.amount)
    }

    @Test fun interestOnlyMovesMoneyWithoutChangingPrincipalRemaining() = runTest {
        val rig = RepaymentTestRig()
        val saved = rig.record(7, 1, usd("0"), day, interest = usd("50"))
        assertEquals(usd("0"), rig.saved(saved.id).principal)
        assertEquals(usd("50"), rig.saved(saved.id).accountAmount)
        assertEquals(usd("5000"), rig.remaining())
    }

    @Test fun nullPrincipalPaysExactlyTheCurrentRest() = runTest {
        val rig = RepaymentTestRig()
        rig.record(7, 1, usd("400"), day)
        val saved = rig.record(7, 1, null, day)
        assertEquals(usd("4600"), rig.saved(saved.id).principal)
        assertEquals(usd("0"), rig.remaining())
    }

    @Test fun anEditMayReuseItsOwnPrincipalWhenTheDebtIsSettled() = runTest {
        val rig = RepaymentTestRig()
        val paid = rig.record(7, 1, null, day)
        rig.record(7, 1, usd("5000"), day, id = paid.id, note = "receipt")
        assertEquals(usd("0"), rig.remaining())
        assertEquals("receipt", rig.saved(paid.id).note)
    }

    @Test fun anEditOvershootReportsRemainingPlusItsOldPrincipal() = runTest {
        val rig = RepaymentTestRig()
        val paid = rig.record(7, 1, usd("400"), day)
        val failure = runCatching { rig.record(7, 1, usd("5001"), day, id = paid.id) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.RepaymentExceedsRemaining)
        assertEquals(usd("5000"), (failure as WalletException.RepaymentExceedsRemaining).remaining)
        assertEquals(usd("400"), rig.saved(paid.id).principal)
    }

    @Test fun movingARepaymentDoesNotAddItsOldPrincipalToTheNewDebtAllowance() = runTest {
        val other = DebtEntryWorld.ali.copy(id = 9, counterparty = "Omar")
        val rig = RepaymentTestRig(debts = listOf(DebtEntryWorld.ali, other),
            initialEntries = listOf(opening(), opening(id = 2, debtId = 9, amount = "50")))
        val paid = rig.record(7, 1, usd("400"), day)
        val failure = runCatching { rig.record(9, 1, usd("100"), day, id = paid.id) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.RepaymentExceedsRemaining)
        assertEquals(usd("50"), (failure as WalletException.RepaymentExceedsRemaining).remaining)
        assertEquals(7L, rig.saved(paid.id).debtId)
    }

    @Test fun movingARepaymentStillGuardsACorruptOldDebtBelowZero() = runTest {
        val other = DebtEntryWorld.ali.copy(id = 9, counterparty = "Omar")
        val paid = Transaction.Repayment(id = 2, side = DebtSide.I_OWE, accountId = 1, debtId = 7,
            accountAmount = usd("100"), principal = usd("100"), date = day)
        val rig = RepaymentTestRig(debts = listOf(DebtEntryWorld.ali, other),
            initialEntries = listOf(opening(amount = "50"), paid, paid.copy(id = 4), opening(id = 3, debtId = 9, amount = "500")))
        val failure = runCatching { rig.record(9, 1, usd("10"), day, id = 2) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(paid, rig.saved(2))
        assertEquals(usd("500"), rig.remaining(9))
    }

    @Test fun anEarlierDateDoesNotRejectAnOtherwiseValidRepayment() = runTest {
        val rig = RepaymentTestRig()
        val earlier = day.minusDays(30)
        val saved = rig.record(7, 1, usd("400"), earlier)
        assertEquals(earlier, saved.date)
        assertEquals(earlier, rig.saved(saved.id).date)
        assertEquals(usd("4600"), rig.remaining())
    }

    @Test fun aMissingDebtIsATypedRefusal() = runTest {
        val rig = RepaymentTestRig()
        val failure = runCatching { rig.record(99, 1, usd("400"), day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtNotFound)
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun aStaleEditedRepaymentIsATypedRefusal() = runTest {
        val rig = RepaymentTestRig()
        val failure = runCatching { rig.record(7, 1, usd("400"), day, id = 99) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.TransactionNotFound)
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun aMissingAccountIsATypedRefusal() = runTest {
        val rig = RepaymentTestRig()
        val failure = runCatching { rig.record(7, 99, usd("400"), day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.AccountNotFound)
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun anArchivedAccountCannotPayARepayment() = runTest {
        val rig = RepaymentTestRig(accounts = listOf(EntryWorld.usdAccount.copy(archived = true)))
        val failure = runCatching { rig.record(7, 1, usd("400"), day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.AccountArchived)
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun principalMustUseTheDebtCurrency() = runTest {
        val rig = RepaymentTestRig()
        val failure = runCatching { rig.record(7, 1, Money.of("400", "EUR"), day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.CurrencyMismatch)
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun interestMustUseTheAccountCurrency() = runTest {
        val rig = RepaymentTestRig()
        val failure = runCatching { rig.record(7, 1, usd("400"), day, interest = Money.of("50", "EUR")) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.CurrencyMismatch)
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun accountPrincipalMustUseTheAccountCurrency() = runTest {
        val rig = RepaymentTestRig()
        val failure = runCatching { rig.record(7, 1, usd("400"), day, accountPrincipal = Money.of("400", "EUR")) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.CurrencyMismatch)
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun aLateRateFillFailureRollsBackPaymentInterestBucketAndRates() = runTest {
        val rig = RepaymentTestRig(faultAfterFill = true)
        val categoriesBefore = rig.store.categoryRepository.stored
        val failure = runCatching {
            rig.record(7, 1, usd("400"), day, typedRates = mapOf("SYP" to BigDecimal("0.0002")), interest = usd("50"))
        }.exceptionOrNull()
        assertTrue("was $failure", failure is InjectedFault)
        assertEquals(usd("5000"), rig.remaining())
        assertEquals(1, rig.store.entries.rows.value.size)
        assertEquals(categoriesBefore, rig.store.categoryRepository.stored)
        assertNull(rig.store.rates.findByCode("SYP"))
    }
    @Test fun collectingAboveRemainingReportsTheAvailableAmountWithoutWriting() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(debtId = 8, side = DebtSide.OWED_TO_ME)))
        val failure = runCatching { rig.record(8, 1, usd("5001"), day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.RepaymentExceedsRemaining)
        assertEquals(usd("5000"), (failure as WalletException.RepaymentExceedsRemaining).remaining)
        assertEquals(1, rig.store.entries.rows.value.size)
        assertEquals(usd("5000"), rig.remaining(8))
    }

}
