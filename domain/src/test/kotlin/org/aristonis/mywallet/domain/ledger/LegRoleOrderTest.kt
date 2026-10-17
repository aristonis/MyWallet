package org.aristonis.mywallet.domain.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stored role names and CSV ordering remain stable as new roles are added. */
class LegRoleOrderTest {

    @Test
    fun everyExistingRoleKeepsItsNameAndOrdinal() {
        val expectedOrdinals = mapOf(
            "ACCOUNT" to 0,
            "SOURCE" to 1,
            "DEST" to 2,
            "DEBT" to 3,
            "MAIN" to 4,
            "FEE" to 5,
            "DEDUCTION" to 6,
            "DEDUCTION_DEBT" to 7,
            "INTEREST_PAID" to 8,
            "INTEREST_RECEIVED" to 9,
            "LOSS" to 10,
            "FORGIVEN" to 11,
        )

        val actualOrdinals = expectedOrdinals.keys.associateWith { LegRole.valueOf(it).ordinal }

        assertEquals(expectedOrdinals, actualOrdinals)
    }

    @Test
    fun paidLaterIsAppendedAfterEveryExistingRole() {
        val paidLaterName = "PAID_LATER"

        val paidLater = LegRole.valueOf(paidLaterName)

        assertEquals(12, paidLater.ordinal)
        assertEquals(paidLater, LegRole.entries.last())
    }
}
