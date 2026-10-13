package org.aristonis.mywallet.domain.model

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

/** A fee rule is checked where it is made, so every writer (form, restore, use case) gets one answer. */
class FeeRuleTest {

    @Test
    fun aPercentageOfExactlyOneHundredIsAccepted() {
        assertEquals(BigDecimal("100.00"), FeeRule(FeeMode.PERCENT, BigDecimal("100.00")).value)
    }

    @Test
    fun aPercentageAboveOneHundredIsRefusedWithItsOwnError() {
        val refused = assertThrows(WalletException.FeePercentAboveHundred::class.java) {
            FeeRule(FeeMode.PERCENT, BigDecimal("150"))
        }
        assertEquals(BigDecimal("150"), refused.percent)
        assertThrows(WalletException.FeePercentAboveHundred::class.java) {
            FeeRule(FeeMode.PERCENT, BigDecimal("100.001"))
        }
    }

    @Test
    fun aFixedFeeAboveOneHundredIsAnAmountAndIsAccepted() {
        assertEquals(BigDecimal("150"), FeeRule(FeeMode.FIXED, BigDecimal("150")).value)
    }

    @Test
    fun aNegativeValueIsStillRefused() {
        assertThrows(IllegalArgumentException::class.java) { FeeRule(FeeMode.PERCENT, BigDecimal("-1")) }
    }
}
