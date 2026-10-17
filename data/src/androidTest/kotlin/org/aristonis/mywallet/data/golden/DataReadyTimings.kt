package org.aristonis.mywallet.data.golden

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.data.db.ALL_MIGRATIONS
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.repo.RoomAccountRepository
import org.aristonis.mywallet.data.repo.RoomFxRepository
import org.aristonis.mywallet.data.repo.RoomEntryRepository
import org.aristonis.mywallet.data.repo.RoomLedgerQueries
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.aristonis.mywallet.domain.usecase.ComputeHomeSummary
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import java.time.YearMonth

/**
 * Median milliseconds, over [runs] after one warm-up, for the first complete value of each screen's
 * read on a copy of the seed: Home (balances in base plus the Home summary), the all-time transactions
 * list, and Tracking for the latest month and for all time.
 *
 * The transactions list opens on one page of the newest entries, so its timing is that first page
 * read, the same read the screen waits for, not the whole history.
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
        val ledger = RoomLedgerQueries(db.ledgerQueryDao())
        val balances = GetAccountBalances(RoomAccountRepository(db.accountDao()), ledger)
        val inBase = GetAccountBalancesInBase(fx)
        val summary = ComputeHomeSummary(ledger, fx)
        val breakdown = ComputeCategoryBreakdown(ledger, fx)
        val dates = transactions.observeAll().first().map { it.date }
        check(dates.isNotEmpty()) { "seed asset $asset holds no transactions" }
        val lastMonth = YearMonth.from(dates.max())
        val reads: Map<String, suspend () -> Unit> = linkedMapOf(
            "home" to {
                coroutineScope {
                    val shared = balances().shareIn(this, SharingStarted.Lazily, replay = 1)
                    try { combine(inBase(shared), summary(shared)) { a, b -> a to b }.first() }
                    finally { coroutineContext.cancelChildren() }
                }
            },
            "transactions-all-time" to {
                transactions.observePage(DateRange.ALL_TIME, after = null, limit = LIST_FIRST_READ).first()
            },
            "tracking-month" to { breakdown(DateRange(lastMonth.atDay(1), lastMonth.atEndOfMonth())).first() },
            // The screen asks for all time as DateRange.ALL_TIME, which takes the legs-led read.
            "tracking-all-time" to { breakdown(DateRange.ALL_TIME).first() },
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

/** What the transactions list asks for when it opens: one page of 100 plus one to tell if more exist. */
private const val LIST_FIRST_READ = 101

/** Nanoseconds retain useful precision when a cached page takes less than a millisecond. */
internal suspend fun captureFilteredTimings(db: WalletDatabase, runs: Int = 7): Map<String, Long> {
    require(runs > 0 && runs % 2 == 1)
    val repository = RoomEntryRepository(db, db.entryDao())
    val dates = repository.observeAll().first().map { it.date }
    check(dates.isNotEmpty()) { "timing seed holds no entries" }
    val month = YearMonth.from(dates.max())
    val ranges = linkedMapOf("month" to DateRange(month.atDay(1), month.atEndOfMonth()),
        "all-time" to DateRange.ALL_TIME)
    val result = linkedMapOf<String, Long>()
    for ((label, range) in ranges) {
        suspend fun read(type: org.aristonis.mywallet.domain.model.TypeGroup) {
            repository.observePage(range, null, LIST_FIRST_READ,
                org.aristonis.mywallet.domain.model.TransactionFilter(types = if (type == org.aristonis.mywallet.domain.model.TypeGroup.ALL) emptySet() else setOf(type))).first()
        }
        for (type in org.aristonis.mywallet.domain.model.TypeGroup.entries) read(type)
        val samples = org.aristonis.mywallet.domain.model.TypeGroup.entries.associateWith { mutableListOf<Long>() }
        // Rotate order so the same chip does not always pay the first-read/cache cost.
        repeat(runs) { run ->
            val types = org.aristonis.mywallet.domain.model.TypeGroup.entries
            for (offset in types.indices) {
                val type = types[(run + offset) % types.size]
                val start = System.nanoTime()
                read(type)
                samples.getValue(type) += System.nanoTime() - start
            }
        }
        for ((type, values) in samples) result["$label/${type.name}"] = values.sorted()[runs / 2]
    }
    return result
}
