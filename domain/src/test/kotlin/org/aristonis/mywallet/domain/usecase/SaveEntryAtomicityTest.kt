package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.InjectedFault
import org.aristonis.mywallet.domain.usecase.fake.RollbackOnFailureRunner
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * A save is one unit. The repository fails right after it has written the legs; the entry, and any
 * rate typed for it, must not survive.
 */
class SaveEntryAtomicityTest {

    @Test
    fun aFaultAfterTheLegsAreWrittenSavesNothing() = runTest {
        val rig = SaveEntryRig(faultAfterWrite = true, atomic = true)

        val failure = runCatching { rig.saveEntry(EntryWorld.expense(), typedRates = emptyMap()) }.exceptionOrNull()

        assertTrue("was $failure", failure is InjectedFault)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun aFaultDuringAnEditLeavesTheStoredEntryAsItWas() = runTest {
        val original = EntryWorld.expense(id = 3, rate = "1", note = "keep")
        val rig = SaveEntryRig(initialEntries = listOf(original), faultAfterWrite = true, atomic = true)

        runCatching { rig.saveEntry(EntryWorld.expense(id = 3, rate = "1", note = "lost"), typedRates = emptyMap()) }

        assertEquals(original, rig.entries.findById(3))
    }

    @Test
    fun aFaultAlsoUndoesTheRateTypedForTheEntry() = runTest {
        val rig = SaveEntryRig(
            savedRates = mapOf("SYP" to "0.0001"),
            saveRates = true,
            faultAfterWrite = true,
            atomic = true,
        )

        runCatching {
            rig.saveEntry(
                EntryWorld.expense(accountId = 2, amount = "5000", currency = "SYP"),
                typedRates = mapOf("SYP" to BigDecimal("0.0009")),
            )
        }

        assertTrue(rig.entries.rows.value.isEmpty())
        assertEquals(0, BigDecimal("0.0001").compareTo(rig.rates.findByCode("SYP")!!.rateToBase))
    }

    @Test
    fun aSuccessfulSaveRunsInExactlyOneTransaction() = runTest {
        val rig = SaveEntryRig(atomic = true)

        rig.saveEntry(EntryWorld.expense(), typedRates = emptyMap())

        assertEquals(1, (rig.runner as RollbackOnFailureRunner).runs)
        assertEquals(1, rig.entries.rows.value.size)
    }
}
