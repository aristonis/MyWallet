package org.aristonis.mywallet.ui.message

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class DeductionUiMessageMappingTest {
    @Test fun aPercentageOutsideTheRangeHasANamedMessageWithoutTheValue() {
        assertEquals(
            UiMessage.DeductionOutOfRange,
            WalletException.DeductionOutOfRange(BigDecimal("101")).toUiMessage(),
        )
        assertEquals(
            WalletException.DeductionOutOfRange(BigDecimal("0")).toUiMessage(),
            WalletException.DeductionOutOfRange(BigDecimal("-5")).toUiMessage(),
        )
    }

    @Test fun aFixedValueNotAboveZeroHasItsOwnMessage() {
        assertEquals(
            UiMessage.DeductionValueNotPositive,
            WalletException.DeductionValueNotPositive(BigDecimal("0")).toUiMessage(),
        )
    }

    @Test fun deductionsAboveTheIncomeHaveANamedMessage() {
        val gross = Money.of("100.00", "USD")
        val total = Money.of("100.01", "USD")
        assertEquals(
            UiMessage.DeductionsExceedIncome,
            WalletException.DeductionsExceedIncome(gross, total).toUiMessage(),
        )
    }

    @Test fun anItemInUseAndAMissingItemHaveNamedMessagesWithoutTheirIds() {
        assertEquals(UiMessage.DeductionItemInUse, WalletException.DeductionItemInUse(3).toUiMessage())
        assertEquals(UiMessage.DeductionItemNotFound, WalletException.DeductionItemNotFound(3).toUiMessage())
    }
}
