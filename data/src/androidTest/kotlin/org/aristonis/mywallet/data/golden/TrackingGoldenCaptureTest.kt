package org.aristonis.mywallet.data.golden

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.time.YearMonth

/**
 * Records what the current Tracking shows for every month of two seed databases, so the next storage
 * model can be held to exactly these numbers. The second seed exists for what the first cannot show:
 * a fractional rate, conversions that land on a half, a base currency with decimals, and months with
 * one or two missing rates. Each capture is also written to the app's files so a new golden can be
 * pulled, and it must equal the golden already committed. Goes away once the schema is replaced.
 */
@RunWith(AndroidJUnit4::class)
class TrackingGoldenCaptureTest {

    @Test
    fun theMainSeedMatchesItsCommittedGolden() = runTest { checkCapture("v2-seed/wallet.db", "tracking-golden-1.1.1.txt") }

    @Test
    fun theEdgeSeedMatchesItsCommittedGolden() = runTest { checkCapture("v2-edge/wallet.db", "tracking-golden-edge-1.1.1.txt") }

    @Test
    fun theEdgeSeedShowsAMonthWithOneMissingRateAndAMonthWithTwo() = runTest {
        val lines = captureTrackingGolden(ApplicationProvider.getApplicationContext(), "v2-edge/wallet.db").lines
        assertTrue(lines.count { MISSING.matches(it) } >= 2)
        assertTrue(lines.any { line -> line.substringAfter(' ').let { it.contains('.') && !it.startsWith("MISSING") } })
    }

    private suspend fun checkCapture(seed: String, golden: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val capture = captureTrackingGolden(context, seed)
        File(context.filesDir, golden).writeText(capture.lines.joinToString("\n", postfix = "\n"))

        val months = generateSequence(YearMonth.from(capture.firstDate)) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(YearMonth.from(capture.lastDate)) }
            .toList()
        assertEquals(months.size, capture.lines.size)
        capture.lines.zip(months).forEach { (line, month) ->
            assertTrue(line, line.startsWith("$month "))
            assertTrue(line, LINE.matches(line))
        }
        val committed = try {
            InstrumentationRegistry.getInstrumentation().context.assets.open(golden).bufferedReader().readLines()
        } catch (missing: FileNotFoundException) {
            throw AssertionError("no committed golden $golden; pull it from files/ after this run", missing)
        }
        assertEquals(committed, capture.lines)
    }

    private companion object {
        val LINE = Regex("""\d{4}-\d{2} (-?\d+(\.\d+)? -?\d+(\.\d+)?|MISSING [A-Z]{3})""")
        val MISSING = Regex("""\d{4}-\d{2} MISSING [A-Z]{3}""")
    }
}
