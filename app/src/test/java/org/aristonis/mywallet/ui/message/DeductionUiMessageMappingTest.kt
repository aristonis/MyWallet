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

    @Test fun aDeductionOverrideInAnotherCurrencyHasAGenericMessage() {
        assertEquals(
            UiMessage.DeductionOverrideCurrencyMismatch,
            WalletException.DeductionOverrideCurrencyMismatch("EUR", "USD").toUiMessage(),
        )
    }

    @Test fun anItemInUseAndAMissingItemHaveNamedMessagesWithoutTheirIds() {
        assertEquals(UiMessage.DeductionItemInUse, WalletException.DeductionItemInUse(3).toUiMessage())
        assertEquals(UiMessage.DeductionItemNotFound, WalletException.DeductionItemNotFound(3).toUiMessage())
    }

    @Test fun aDuplicateDeductionItemNameHasANamedMessageWithoutTheName() {
        assertEquals(UiMessage.DeductionItemNameTaken, WalletException.DeductionItemNameTaken("Tax").toUiMessage())
    }

    @Test fun aFeeThatConsumesTheSentAmountHasANamedMessage() {
        assertEquals(
            UiMessage.FeeNotBelowAmount,
            WalletException.FeeNotBelowAmount(Money.of("10", "USD"), Money.of("10", "USD")).toUiMessage(),
        )
    }

    @Test fun anInvalidSnapshotHasAnActionableMessageWithoutItsFields() {
        assertEquals(UiMessage.DeductionSnapshotInvalid, WalletException.DeductionSnapshotInvalid().toUiMessage())
    }

    @Test fun aNegativeFeeUsesTheNonnegativeFeeMessage() {
        assertEquals(UiMessage.FeeValueNegative, WalletException.FeeValueNegative(Money.of("-1", "USD")).toUiMessage())
    }

    @Test fun aFeeInputTheFormRefusesKeepsItsNamedMessage() {
        val refused: Throwable = RefusedInputException(UiMessage.FeePercentAboveHundred)
        assertEquals(UiMessage.FeePercentAboveHundred, refused.toUiMessage())
    }
}
