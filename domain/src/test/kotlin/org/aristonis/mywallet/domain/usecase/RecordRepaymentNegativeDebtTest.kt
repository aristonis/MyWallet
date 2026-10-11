package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.day
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.opening
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.syp
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.usd
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Editing a repayment on a debt that was already overpaid: it may improve, never get worse. */
class RecordRepaymentNegativeDebtTest {
    private fun repayment(id: Long, amount: String, debtId: Long = 7) = Transaction.Repayment(
        id = id, side = DebtSide.I_OWE, accountId = 1, debtId = debtId,
        accountAmount = usd(amount), principal = usd(amount), date = day,
    )

    @Test fun raisingARepaymentOnAnOverpaidDebtIsRefusedAndLeavesTheEntryAlone() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(amount = "50"), repayment(2, "100")))
        val failure = runCatching { rig.record(7, 1, usd("110"), day, id = 2) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(usd("60"), (failure as WalletException.DebtBelowZero).amount)
        assertEquals(usd("100"), rig.saved(2).principal)
        assertEquals(usd("-50"), rig.remaining())
    }

    @Test fun payingTheRestOnAnEditOfAnOverpaidDebtSettlesAtExactlyZero() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(amount = "50"), repayment(2, "100")))
        rig.record(7, 1, null, day, id = 2)
        assertEquals(usd("50"), rig.saved(2).principal)
        assertEquals(usd("0"), rig.remaining())
    }

    @Test fun payingTheRestOfOneRepaymentIsRefusedWhenAnotherOverpaidTheDebtFurther() = runTest {
        val rig = RepaymentTestRig(
            initialEntries = listOf(opening(amount = "50"), repayment(2, "100"), repayment(3, "30")),
        )
        val failure = runCatching { rig.record(7, 1, null, day, id = 3) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(usd("50"), (failure as WalletException.DebtBelowZero).amount)
        assertEquals(usd("30"), rig.saved(3).principal)
        assertEquals(usd("-80"), rig.remaining())
        assertEquals(3, rig.store.entries.rows.value.size)
    }

    @Test fun aNoteOnlyEditOfAnOverpaidRepaymentIsAllowed() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(amount = "50"), repayment(2, "70")))
        rig.record(7, 1, usd("70"), day, note = "receipt", id = 2)
        assertEquals("receipt", rig.saved(2).note)
        assertEquals(usd("-20"), rig.remaining())
    }

    @Test fun payingTheRestAcrossCurrenciesOnAnOverpaidDebtUsesTheStoredPairRate() = runTest {
        val original = Transaction.Repayment(
            id = 2, side = DebtSide.I_OWE, accountId = 1, debtId = 7,
            accountAmount = usd("115.38"), principal = syp("1500000"), pairRate = BigDecimal("13000"), date = day,
        )
        val rig = RepaymentTestRig(
            debts = listOf(DebtEntryWorld.ali.copy(currencyCode = "SYP")),
            initialEntries = listOf(opening(amount = "1000050", currency = "SYP"), original),
        )
        rig.record(7, 1, null, day, id = 2)
        val edited = rig.saved(2)
        assertEquals(syp("1000050"), edited.principal)
        assertEquals(usd("76.93"), edited.accountAmount)
        assertEquals(0, BigDecimal("13000").compareTo(edited.pairRate))
        assertEquals(syp("0"), rig.remaining())
    }
}
