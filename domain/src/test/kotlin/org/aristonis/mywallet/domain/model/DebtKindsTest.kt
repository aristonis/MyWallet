package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

/** The checks each debt kind makes on construction, so an illegal entry cannot exist. */
class DebtKindsTest {

    private val day = LocalDate.of(2026, 5, 20)
    private fun usd(a: String) = Money.of(a, "USD")

    private fun rejected(build: () -> Transaction) {
        assertThrows(IllegalArgumentException::class.java) { build() }
    }

    @Test
    fun borrowAndLendNeedAPositiveAmount() {
        assertEquals(usd("1"), Transaction.Borrow(accountId = 1, debtId = 2, amount = usd("1"), date = day).amount)
        listOf("0", "-5").forEach {
            rejected { Transaction.Borrow(accountId = 1, debtId = 2, amount = usd(it), date = day) }
            rejected { Transaction.Lend(accountId = 1, debtId = 2, amount = usd(it), date = day) }
        }
    }

    @Test
    fun owedBeforeNeedsAPositiveAmount() {
        listOf("0", "-5").forEach {
            rejected { Transaction.OwedBefore(debtId = 2, side = DebtSide.I_OWE, amount = usd(it), date = day) }
        }
    }

    @Test
    fun creditPurchaseAndEarnedUnpaidNeedAPositiveAmount() {
        listOf("0", "-5").forEach {
            rejected { Transaction.CreditPurchase(debtId = 2, amount = usd(it), categoryId = 3, date = day) }
            rejected { Transaction.EarnedUnpaid(debtId = 2, amount = usd(it), categoryId = 3, date = day) }
        }
    }

    @Test
    fun writeOffNeedsAPositiveAmount() {
        listOf("0", "-5").forEach {
            rejected { Transaction.WriteOff(side = DebtSide.OWED_TO_ME, debtId = 2, amount = usd(it), categoryId = 3, date = day) }
        }
    }

    private fun repayment(accountAmount: String = "50", principal: String = "0", interest: String? = "50") =
        Transaction.Repayment(
            side = DebtSide.I_OWE, accountId = 1, debtId = 2, accountAmount = usd(accountAmount),
            principal = usd(principal), interest = interest?.let { usd(it) }, interestCategoryId = 4, date = day,
        )

    @Test
    fun aRepaymentWithNoPrincipalAndNoInterestMovesNothingAndIsRejected() {
        rejected { repayment(accountAmount = "50", principal = "0", interest = null) }
        rejected {
            Transaction.Repayment(
                side = DebtSide.OWED_TO_ME, accountId = 1, debtId = 2, accountAmount = usd("50"),
                principal = usd("0"), date = day,
            )
        }
    }

    @Test
    fun aRepaymentMayHaveAZeroPrincipalButNeverANegativeOne() {
        assertEquals(usd("0"), repayment().principal)
        rejected { repayment(accountAmount = "50", principal = "-1", interest = "51") }
    }

    @Test
    fun aRepaymentNeedsAPositiveAccountAmount() {
        rejected { repayment(accountAmount = "0", principal = "0", interest = null) }
        rejected { repayment(accountAmount = "-5", principal = "0", interest = null) }
    }

    @Test
    fun aRepaymentInterestIsAbsentOrAboveZero() {
        assertEquals(null, repayment(accountAmount = "10", principal = "10", interest = null).interest)
        rejected { repayment(accountAmount = "10", principal = "10", interest = "0") }
        rejected { repayment(accountAmount = "10", principal = "20", interest = "-10") }
    }
}
