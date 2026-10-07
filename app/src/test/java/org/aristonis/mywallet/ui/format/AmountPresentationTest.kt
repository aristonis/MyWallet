package org.aristonis.mywallet.ui.format

import org.aristonis.mywallet.ui.transaction.TransactionRowType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sign a ledger amount carries, and which ledger role it reads as. Both used to be re-derived
 * inline on every screen that showed an amount; they live here so a row, the hero and a report all
 * agree on what "+" means and never drift apart.
 */
class AmountPresentationTest {

    @Test
    fun `income is prefixed with a plus`() {
        assertEquals("+1,500.00 USD", signedAmount(AmountRole.INCOME, "1,500.00 USD"))
    }

    @Test
    fun `expense is prefixed with a real minus sign, not a hyphen`() {
        val signed = signedAmount(AmountRole.EXPENSE, "12.50 USD")

        assertEquals("−12.50 USD", signed)
        assertEquals(false, signed.contains('-'))
    }

    @Test
    fun `a neutral amount carries no sign`() {
        assertEquals("100.00 USD", signedAmount(AmountRole.NEUTRAL, "100.00 USD"))
    }

    @Test
    fun `income and expense rows read as their own role`() {
        assertEquals(AmountRole.INCOME, TransactionRowType.INCOME.amountRole())
        assertEquals(AmountRole.EXPENSE, TransactionRowType.EXPENSE.amountRole())
    }

    @Test
    fun `a transfer is neutral because the money never left the wallet`() {
        assertEquals(AmountRole.NEUTRAL, TransactionRowType.TRANSFER.amountRole())
    }
}
