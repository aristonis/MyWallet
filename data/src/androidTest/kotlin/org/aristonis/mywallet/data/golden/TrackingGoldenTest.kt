package org.aristonis.mywallet.data.golden

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.db.ALL_MIGRATIONS
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.repo.RoomFxRepository
import org.aristonis.mywallet.data.repo.RoomLedgerQueries
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.usecase.CategoryBreakdownResult
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

/**
 * The upgrade must not move a single Tracking number. Each seed database (schema 2, shipped as a test
 * asset) is opened so the real migration to schema 3 runs, Tracking is computed for every calendar
 * month from the first entry to the last through the same use case the app wires over Room, and the
 * text is compared byte for byte with what the previous release showed for the same months.
 *
 * Assets under `data/src/androidTest/assets/`: `v2-seed/wallet.db` with `tracking-golden-1.1.1.txt`, and
 * `v2-edge/wallet.db` (fractional rates, half-way conversions, months with a missing rate) with
 * `tracking-golden-edge-1.1.1.txt`. Each line is `YYYY-MM <income> <expense>` in plain decimals, or
 * `YYYY-MM MISSING <code>`; the file ends with a newline. The golden files are never regenerated.
 *
 * Assumed wiring (as the app's DI): `ComputeCategoryBreakdown(RoomLedgerQueries(db.ledgerQueryDao()), RoomFxRepository(db))`.
 */
@RunWith(AndroidJUnit4::class)
class TrackingGoldenTest {

    @Test
    fun theMigratedMainSeedShowsTheSameTrackingForEveryMonthAsBefore() = runTest {
        assertSameAsGolden("v2-seed/wallet.db", "tracking-golden-1.1.1.txt")
    }

    @Test
    fun theMigratedEdgeSeedShowsTheSameTrackingForEveryMonthAsBefore() = runTest {
        assertSameAsGolden("v2-edge/wallet.db", "tracking-golden-edge-1.1.1.txt")
    }

    @Test
    fun theEdgeSeedStillHasMonthsWithAMissingRateAndFractionalTotals() = runTest {
        val lines = trackingTextOf("v2-edge/wallet.db").lines().filter { it.isNotEmpty() }

        assertTrue(lines.count { it.contains(" MISSING ") } >= 2)
        assertTrue(lines.any { line -> !line.contains("MISSING") && line.substringAfter(' ').contains('.') })
    }

    private suspend fun assertSameAsGolden(seed: String, golden: String) {
        val expected = InstrumentationRegistry.getInstrumentation().context.assets.open(golden).use { String(it.readBytes(), Charsets.UTF_8) }

        val actual = trackingTextOf(seed)

        assertEquals(expected.lines().size, actual.lines().size)
        assertEquals(expected, actual)
    }

    /** One line per calendar month from the first entry to the last, each ending in a newline. */
    private suspend fun trackingTextOf(asset: String): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "golden-proof.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val db = Room.databaseBuilder(context, WalletDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).build()
        try {
            val tracking = ComputeCategoryBreakdown(RoomLedgerQueries(db.ledgerQueryDao()), RoomFxRepository(db))
            val (first, last) = db.openHelper.writableDatabase.query("SELECT MIN(date), MAX(date) FROM entries").use { cursor ->
                check(cursor.moveToFirst() && !cursor.isNull(0)) { "seed asset $asset holds no entries" }
                LocalDate.ofEpochDay(cursor.getLong(0)) to LocalDate.ofEpochDay(cursor.getLong(1))
            }
            return generateSequence(YearMonth.from(first)) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(YearMonth.from(last)) }
                .toList()
                .map { month -> lineFor(month, tracking(month.range()).first()) } // map is inline, so it can suspend
                .joinToString(separator = "")
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun YearMonth.range() = DateRange(atDay(1), atEndOfMonth())

    private fun lineFor(month: YearMonth, result: CategoryBreakdownResult): String = when (result) {
        is CategoryBreakdownResult.Resolved -> {
            val summary = result.breakdown.summary
            "$month ${summary.income.amount.toPlainString()} ${summary.expense.amount.toPlainString()}\n"
        }
        is CategoryBreakdownResult.MissingRate -> "$month MISSING ${result.currencyCode}\n"
    }
}
