package org.aristonis.mywallet.data.golden

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.TypeGroup
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class FilteredTimingCaptureTest {
    @Test fun eachChipMedianIsWithinTwentyPercentOfAllOnSeedAndTenTimesSeed() = runTest(timeout = 10.minutes) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(context.filesDir, "filtered-timing.txt")
        output.writeText("unit=nanoseconds runs=7 warmup=1 page=101\n")
        val violations = mutableListOf<String>()
        for (copies in listOf(1, 10)) {
            val timings = withFilteredSeed(context, "filtered-timing-$copies.db", copies) { captureFilteredTimings(it) }
            for (range in listOf("month", "all-time")) {
                val baseline = timings.getValue("$range/ALL")
                for (type in TypeGroup.entries) {
                    val duration = timings.getValue("$range/${type.name}")
                    val line = "copies=$copies range=$range chip=${type.name} median=$duration baseline=$baseline"
                    output.appendText("$line\n")
                    if (duration * 5 > baseline * 6) violations += line
                }
            }
        }
        assertTrue("NFR-19 failed; Transfer failure requires owner decision before schema/index changes: $violations",
            violations.isEmpty())
    }
}
