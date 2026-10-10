package org.aristonis.mywallet.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The below-zero message puts a typed name next to an amount. An Arabic name in a left-to-right
 * sentence (or the reverse) pulls the amount to the wrong side unless the name is isolated.
 */
class DebtBelowZeroArgsTest {

    private val firstStrongIsolate = "\u2068"
    private val popDirectionalIsolate = "\u2069"

    @Test
    fun theCounterpartyIsIsolatedAndTheAmountIsLeftAsFormatted() {
        val (name, amount) = debtBelowZeroArgs("Ali", "USD 50.00")

        assertEquals(firstStrongIsolate + "Ali" + popDirectionalIsolate, name)
        assertEquals("USD 50.00", amount)
    }

    @Test
    fun anArabicCounterpartyIsIsolatedAsAWhole() {
        val arabic = "\u0645\u062D\u0645\u062F \u0639\u0644\u064A"

        val (name, _) = debtBelowZeroArgs(arabic, "USD 5.00")

        assertEquals(firstStrongIsolate + arabic + popDirectionalIsolate, name)
    }
}
