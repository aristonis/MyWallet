package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeductionMappersTest {
    @Test
    fun invalidPersistedPercentageIsReportedAsAnUnreadableRow() {
        val row = DeductionItemEntity(
            id = 41,
            name = "Tax",
            nameKey = "tax",
            mode = "PERCENT",
            value = "120",
            valueCurrencyCode = null,
            timing = "NOW",
            expenseCategoryId = 3,
            enabled = true,
            sortOrder = 0,
        )

        val thrown = assertThrows(WalletException.UnreadableRow::class.java) { row.toDomain() }

        assertEquals(41L, thrown.id)
        assertEquals("deduction item", thrown.kind)
        assertTrue(thrown.cause is WalletException.DeductionOutOfRange)
    }

    @Test
    fun invalidPersistedFixedAmountIsReportedAsAnUnreadableRow() {
        val row = DeductionItemEntity(
            id = 42,
            name = "Insurance",
            nameKey = "insurance",
            mode = "FIXED",
            value = "0",
            valueCurrencyCode = "USD",
            timing = "NOW",
            expenseCategoryId = 3,
            enabled = true,
            sortOrder = 0,
        )

        val thrown = assertThrows(WalletException.UnreadableRow::class.java) { row.toDomain() }

        assertEquals(42L, thrown.id)
        assertEquals("deduction item", thrown.kind)
        assertTrue(thrown.cause is WalletException.DeductionValueNotPositive)
    }
}
