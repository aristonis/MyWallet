package org.aristonis.mywallet.ui.message

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryAmountUiMessageMappingTest {
    @Test
    fun aFeeAndDeductionsAboveIncomeHaveTheirOwnMessage() {
        val failure: Throwable = WalletException.FeeAndDeductionsExceedIncome(
            gross = Money.of("1000", "USD"),
            total = Money.of("1005", "USD"),
        )

        val message = failure.toUiMessage()

        assertEquals(UiMessage.FeeAndDeductionsExceedIncome, message)
    }

    @Test
    fun aPaidNowAmountEqualToTheExpenseHasItsOwnMessage() {
        val failure: Throwable = WalletException.PaidNowNotBelowAmount(
            amount = Money.of("1000", "USD"),
            paidNow = Money.of("1000", "USD"),
        )

        val message = failure.toUiMessage()

        assertEquals(UiMessage.PaidNowNotBelowAmount, message)
    }

    @Test
    fun aPaidNowAmountAboveTheExpenseUsesTheSameMessage() {
        val failure: Throwable = WalletException.PaidNowNotBelowAmount(
            amount = Money.of("1000", "USD"),
            paidNow = Money.of("1000.01", "USD"),
        )

        val message = failure.toUiMessage()

        assertEquals(UiMessage.PaidNowNotBelowAmount, message)
    }

    @Test
    fun aPaidLaterFeeWithoutAPaidNowAmountHasItsOwnMessage() {
        val failure: Throwable = WalletException.PaidLaterFeeWithoutPaidNow()

        val message = failure.toUiMessage()

        assertEquals(UiMessage.PaidLaterFeeWithoutPaidNow, message)
    }
}
