package org.aristonis.mywallet.ui.load

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.ui.HealthLedger
import org.aristonis.mywallet.ui.WriteTickingLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ledger's health as a value: it emits only when the answer changes, so a write that leaves the
 * ledger healthy does not make a screen rebuild its page, and only an unreadable row is reported as
 * one. Any other failure keeps its own type and is left to the screen's generic failure handling.
 *
 * Assumed behaviour of `ObserveLedgerHealth.readable(errors, screen)`: `distinctUntilChanged` on the
 * value, and a catch that handles `WalletException.UnreadableRow` only (everything else is rethrown).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedgerReadableTest {

    private val errors = RecordingErrorReporter()

    @Test
    fun `two writes with a healthy ledger produce one emission`() = runTest {
        val ledger = WriteTickingLedger()
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { ledger.health().readable(errors, "Test").collect { seen += it } }
        runCurrent()

        ledger.write()
        ledger.write()
        runCurrent()

        assertEquals(listOf(true), seen)
    }

    @Test
    fun `a row that becomes unreadable turns the value false once and is reported`() = runTest {
        val ledger = WriteTickingLedger()
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { ledger.health().readable(errors, "Test").collect { seen += it } }
        runCurrent()

        ledger.plantUnknownKind(id = 7)
        ledger.write()
        runCurrent()

        assertEquals(listOf(true, false), seen)
        assertEquals(7L, (errors.reported.single() as WalletException.UnreadableRow).id)
    }

    @Test
    fun `a failure that is not an unreadable row is not turned into false`() = runTest {
        val ledger = HealthLedger()
        val failure = IllegalStateException("disk I/O error")
        ledger.healthFailure = failure
        val seen = mutableListOf<Boolean>()

        val thrown = runCatching { ledger.health().readable(errors, "Test").toList(seen) }.exceptionOrNull()

        assertTrue("the original failure passes through: $thrown", thrown === failure)
        assertTrue("no value says the data cannot be read", seen.isEmpty())
        assertTrue("it is not reported as an unreadable row", errors.reported.none { it is WalletException.UnreadableRow })
    }
}
