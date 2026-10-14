package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryFieldErrorRoutingTest {
    @Test
    fun roundedZeroConversionStaysAtFormLevel() {
        val error = UiMessage.AmountRoundsToZero("EUR")

        assertFalse(error.isAmountEntryError())
        assertFalse(error.isInlineEntryError())
    }

    @Test
    fun primaryAmountFailuresAppearBesideAmountField() {
        val errors = listOf(
            UiMessage.AmountMissing,
            UiMessage.AmountNotPositive,
        )

        errors.forEach { error ->
            assertTrue(error.isAmountEntryError())
            assertTrue(error.isInlineEntryError())
        }
    }

    @Test
    fun notANumberNeedsPrimaryAmountProvenance() {
        assertFalse(UiMessage.NotANumber.isAmountEntryError())
        assertFalse(UiMessage.NotANumber.isInlineEntryError())
        assertTrue(UiMessage.NotANumber.isAmountEntryError(errorIsAmountInput = true))
        assertTrue(UiMessage.NotANumber.isInlineEntryError(errorIsAmountInput = true))
    }

    @Test
    fun amountOutOfRangeNeedsPrimaryAmountProvenance() {
        assertFalse(UiMessage.AmountOutOfRange.isAmountEntryError())
        assertFalse(UiMessage.AmountOutOfRange.isInlineEntryError())
        assertTrue(UiMessage.AmountOutOfRange.isAmountEntryError(errorIsAmountInput = true))
        assertTrue(UiMessage.AmountOutOfRange.isInlineEntryError(errorIsAmountInput = true))
    }
}
