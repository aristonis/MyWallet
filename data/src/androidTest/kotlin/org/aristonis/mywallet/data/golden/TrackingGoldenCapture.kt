package org.aristonis.mywallet.data.golden

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.repo.RoomFxRepository
import org.aristonis.mywallet.data.repo.RoomTransactionRepository
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.usecase.CategoryBreakdownResult
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import java.time.LocalDate
import java.time.YearMonth

internal data class GoldenCapture(val firstDate: LocalDate, val lastDate: LocalDate, val lines: List<String>)

/**
 * Opens a copy of the seed database (shipped as a test asset) and asks the current Tracking for each
 * calendar month from the first entry to the last: income and expense as plain decimals, or the
 * currency whose rate is missing.
 */
internal suspend fun captureTrackingGolden(context: Context, asset: String): GoldenCapture {
    val name = "golden-seed.db"
    context.deleteDatabase(name)
    val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
    InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
        file.outputStream().use { input.copyTo(it) }
    }
    val db = Room.databaseBuilder(context, WalletDatabase::class.java, name).build()
    try {
        val transactions = RoomTransactionRepository(db.transactionDao())
        val breakdown = ComputeCategoryBreakdown(transactions, RoomFxRepository(db))
        val dates = transactions.observeAll().first().map { it.date }
        check(dates.isNotEmpty()) { "seed asset $asset holds no transactions" }
        val first = dates.min()
        val last = dates.max()
        val lines = generateSequence(YearMonth.from(first)) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(YearMonth.from(last)) }
            .toList()
            .map { month ->
                when (val result = breakdown(DateRange(month.atDay(1), month.atEndOfMonth())).first()) {
                    is CategoryBreakdownResult.Resolved -> {
                        val summary = result.breakdown.summary
                        "$month ${summary.income.amount.toPlainString()} ${summary.expense.amount.toPlainString()}"
                    }
                    is CategoryBreakdownResult.MissingRate -> "$month MISSING ${result.currencyCode}"
                }
            }
        return GoldenCapture(first, last, lines)
    } finally {
        db.close()
        context.deleteDatabase(name)
    }
}
