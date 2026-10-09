package org.aristonis.mywallet.data.golden

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.data.db.ALL_MIGRATIONS
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.repo.RoomAccountRepository
import org.aristonis.mywallet.data.repo.RoomFxRepository
import org.aristonis.mywallet.data.repo.RoomEntryRepository
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.aristonis.mywallet.domain.usecase.ComputeNetWorth
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import java.time.YearMonth

/**
 * Median milliseconds, over [runs] after one warm-up, for the first complete value of each screen's
 * read on a copy of the seed: Home (balances in base plus net worth), the all-time transactions
 * list, and Tracking for the latest month and for all time.
 *
 * The seed asset is a schema-2 database, so opening it runs the upgrade first; the timings start
 * after that, on the entries and legs it produced.
 */
internal suspend fun captureDataReadyTimings(context: Context, asset: String, runs: Int): Map<String, Long> {
    val name = "timing-seed.db"
    context.deleteDatabase(name)
    val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
    InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
        file.outputStream().use { input.copyTo(it) }
    }
    val db = Room.databaseBuilder(context, WalletDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).build()
    try {
        val transactions = RoomEntryRepository(db, db.entryDao())
        val fx = RoomFxRepository(db)
        val balances = GetAccountBalances(RoomAccountRepository(db.accountDao()), transactions)
        val inBase = GetAccountBalancesInBase(balances, fx)
        val netWorth = ComputeNetWorth(balances, fx)
        val breakdown = ComputeCategoryBreakdown(transactions, fx)
        val dates = transactions.observeAll().first().map { it.date }
        check(dates.isNotEmpty()) { "seed asset $asset holds no transactions" }
        val lastMonth = YearMonth.from(dates.max())
        val reads: Map<String, suspend () -> Unit> = linkedMapOf(
            "home" to { combine(inBase(), netWorth()) { a, b -> a to b }.first() },
            "transactions-all-time" to { transactions.observeAll().first() },
            "tracking-month" to { breakdown(DateRange(lastMonth.atDay(1), lastMonth.atEndOfMonth())).first() },
            "tracking-all-time" to { breakdown(DateRange(dates.min(), dates.max())).first() },
        )
        return reads.mapValues { (_, read) ->
            read()
            List(runs) {
                val start = System.nanoTime()
                read()
                (System.nanoTime() - start) / NANOS_PER_MILLI
            }.sorted()[runs / 2]
        }
    } finally {
        db.close()
        context.deleteDatabase(name)
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
