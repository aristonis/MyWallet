package org.aristonis.mywallet.domain.error

import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test

/** Entry validation errors retain the exact amounts needed to explain a refusal. */
class EntryAmountErrorsTest {

    @Test
    fun feeAndDeductionsExceedIncomeKeepsTheExactGrossAndTotal() {
        val gross = Money.of("1000.000000001", "USD")
        val total = Money.of("1000.000000002", "USD")

        val error = WalletException.FeeAndDeductionsExceedIncome(gross, total)

        assertEquals(gross, error.gross)
        assertEquals(total, error.total)
    }

    @Test
    fun paidNowNotBelowAmountKeepsTheExactAmountAndPaidNow() {
        val amount = Money.of("1000.000000001", "USD")
        val paidNow = Money.of("1000.000000002", "USD")

        val error = WalletException.PaidNowNotBelowAmount(amount, paidNow)

        assertEquals(amount, error.amount)
        assertEquals(paidNow, error.paidNow)
    }

    @Test
    fun paidLaterFeeWithoutPaidNowExplainsWhyTheFeeIsRefused() {
        val error = WalletException.PaidLaterFeeWithoutPaidNow()

        assertEquals("A fee requires an amount paid now", error.message)
    }
}
