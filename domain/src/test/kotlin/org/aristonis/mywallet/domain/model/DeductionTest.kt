package org.aristonis.mywallet.domain.model

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

/** The value rules are shared by the saved item and the per-income input: one check, one set of errors. */
class DeductionTest {

    @Test
    fun aPercentageOfExactlyOneHundredIsAccepted() {
        percentInput("100")
        percentItem("100")
    }

    @Test
    fun aPercentageAboveOneHundredIsRefusedOnBothModels() {
        assertThrows(WalletException.DeductionOutOfRange::class.java) { percentInput("100.01") }
        assertThrows(WalletException.DeductionOutOfRange::class.java) { percentItem("120") }
    }

    @Test
    fun aPercentageOfZeroOrLessIsRefusedOnBothModels() {
        assertThrows(WalletException.DeductionOutOfRange::class.java) { percentInput("0") }
        assertThrows(WalletException.DeductionOutOfRange::class.java) { percentInput("-5") }
        assertThrows(WalletException.DeductionOutOfRange::class.java) { percentItem("0") }
    }

    @Test
    fun theRangeMessageSaysMoreThanZeroAndAtMostOneHundred() {
        val error = assertThrows(WalletException.DeductionOutOfRange::class.java) { percentInput("0") }

        assertEquals(true, error.message!!.contains("more than 0 and at most 100"))
    }

    @Test
    fun aFixedValueOfZeroOrLessHasItsOwnTypedErrorOnBothModels() {
        val zero = assertThrows(WalletException.DeductionValueNotPositive::class.java) { fixedInput("0") }
        assertEquals(0, BigDecimal.ZERO.compareTo(zero.value))
        assertThrows(WalletException.DeductionValueNotPositive::class.java) { fixedInput("-1") }
        assertThrows(WalletException.DeductionValueNotPositive::class.java) { fixedItem("0") }
    }

    @Test
    fun aFixedValueAboveOneHundredIsAccepted() {
        fixedInput("5000")
        fixedItem("5000")
    }

    @Test
    fun aFixedDeductionNeedsACurrencyAndAPercentageMustNotHaveOne() {
        assertThrows(IllegalArgumentException::class.java) {
            DeductionInput(DeductionMode.FIXED, BigDecimal.TEN, null, DeductionTiming.NOW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeductionInput(DeductionMode.PERCENT, BigDecimal.TEN, "USD", DeductionTiming.NOW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeductionItem(name = "Tax", mode = DeductionMode.FIXED, value = BigDecimal.TEN, timing = DeductionTiming.NOW, expenseCategoryId = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeductionItem(
                name = "Tax", mode = DeductionMode.PERCENT, value = BigDecimal.TEN, valueCurrencyCode = "USD",
                timing = DeductionTiming.NOW, expenseCategoryId = 1,
            )
        }
    }

    @Test
    fun anItemNeedsANonBlankName() {
        assertThrows(IllegalArgumentException::class.java) {
            DeductionItem(name = "  ", mode = DeductionMode.PERCENT, value = BigDecimal.TEN, timing = DeductionTiming.NOW, expenseCategoryId = 1)
        }
    }

    @Test
    fun anItemDefaultsToEnabledWithNoAutoAddCategories() {
        val item = percentItem("10")

        assertEquals(true, item.enabled)
        assertEquals(emptySet<Long>(), item.autoAddCategoryIds)
        assertEquals(0L, item.id)
    }

    @Test
    fun anInputHasNoAmountOverrideUnlessGiven() {
        assertEquals(null, percentInput("10").amountOverride)
    }

    private fun percentInput(value: String) =
        DeductionInput(DeductionMode.PERCENT, BigDecimal(value), null, DeductionTiming.NOW)

    private fun fixedInput(value: String) =
        DeductionInput(DeductionMode.FIXED, BigDecimal(value), "USD", DeductionTiming.LATER)

    private fun percentItem(value: String) = DeductionItem(
        name = "Tax", mode = DeductionMode.PERCENT, value = BigDecimal(value),
        timing = DeductionTiming.NOW, expenseCategoryId = 1,
    )

    private fun fixedItem(value: String) = DeductionItem(
        name = "Fee", mode = DeductionMode.FIXED, value = BigDecimal(value), valueCurrencyCode = "USD",
        timing = DeductionTiming.NOW, expenseCategoryId = 1,
    )
}
