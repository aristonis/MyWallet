package org.aristonis.mywallet.data.golden

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How long the reads behind Home, Transactions and Tracking take on the 10,000-row seed with the
 * current storage. Frame counts on a debug build swing too much to compare two storage models, and
 * a slow query shows up as late content rather than as a dropped frame, so the time until the data
 * is ready is what gets compared. The numbers the old storage gave are kept as the asset
 * data-ready-1.1.1.txt; this run writes the current ones beside them for the comparison.
 */
@RunWith(AndroidJUnit4::class)
class DataReadyTimingCaptureTest {

    @Test
    fun recordsTheMedianTimeForEachScreensRead() = runTest(timeout = kotlin.time.Duration.parse("5m")) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val timings = captureDataReadyTimings(context, "v2-seed/wallet.db", runs = 7)
        File(context.filesDir, "data-ready-current.txt").writeText(
            timings.entries.joinToString("\n", postfix = "\n") { (read, ms) -> "$read $ms" },
        )

        assertEquals(setOf("home", "transactions-all-time", "tracking-month", "tracking-all-time"), timings.keys)
        timings.values.forEach { assertTrue(it > 0) }
    }
}
