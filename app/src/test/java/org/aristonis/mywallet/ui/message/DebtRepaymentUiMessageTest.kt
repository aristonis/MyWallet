package org.aristonis.mywallet.ui.message

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DebtRepaymentUiMessageTest {
    @Test fun aRepaymentAboveRemainingKeepsTheExactAvailableAmountInItsMessage() {
        val remaining = Money.of("1000050", "SYP")
        assertEquals(UiMessage.RepaymentExceedsRemaining(remaining),
            WalletException.RepaymentExceedsRemaining(remaining).toUiMessage())
        assertNotEquals(UiMessage.Unexpected, WalletException.RepaymentExceedsRemaining(remaining).toUiMessage())
    }

    @Test fun aDebtWithNoRemainingNamesTheCounterpartyInItsMessage() {
        assertEquals(UiMessage.DebtHasNoRemaining("Sara"), WalletException.DebtHasNoRemaining("Sara").toUiMessage())
        assertNotEquals(UiMessage.Unexpected, WalletException.DebtHasNoRemaining("Sara").toUiMessage())
    }
}
