package org.aristonis.mywallet.ui.reports

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.csv.RfcCsvEncoder
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.port.CategoryLeg
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CsvCategoryLeg
import org.aristonis.mywallet.domain.port.CsvExportSnapshot
import org.aristonis.mywallet.domain.port.CsvExportSource
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.LegRate
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.aristonis.mywallet.domain.usecase.ExportTrackingCsv
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.settings.DocumentIo
import org.aristonis.mywallet.ui.transaction.FakeFxRepository
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/** CSV preparation freezes the visible window before a document can be picked or written. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsCsvExportTest {
    private val dispatcher = StandardTestDispatcher()
    private val day = LocalDate.of(2026, 10, 12)
    private val currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun expense(id: Long, date: LocalDate = day) =
        Transaction.Expense(id, 1, Money.of("1.005", "USD"), 7, date = date, note = "Arabic العربية")

    private class RecordingSource(private val entries: List<Transaction>) : CsvExportSource {
        val requestedRanges = mutableListOf<DateRange>()
        var failure: Throwable? = null
        var blocked: CompletableDeferred<Unit>? = null
        override suspend fun read(range: DateRange): CsvExportSnapshot {
            requestedRanges += range
            blocked?.await()
            failure?.let { throw it }
            val inside = entries.filter { it.date in range }
            val legs = inside.flatMap { entry ->
                val parts = entry.toParts()
                parts.legs.mapNotNull { leg ->
                    val target = leg.target as? LegTarget.Category ?: return@mapNotNull null
                    CsvCategoryLeg(CategoryLeg(entry.id, entry.date, target.id, target.subId,
                        parts.header.kind, leg.role, leg.amount, leg.rateToBase), leg.position)
                }
            }
            return CsvExportSnapshot(legs, inside,
                FxSnapshot("USD", emptyMap(), listOf(Currency("USD", "$", 2))),
                emptyList(), emptyList(), emptyList())
        }
    }

    private fun vm(
        source: RecordingSource,
        ledger: CsvTestLedger = CsvTestLedger(emptyList()),
        errors: RecordingErrorReporter = RecordingErrorReporter(),
        saved: SavedStateHandle = SavedStateHandle(),
    ) = ReportsViewModel(
        computeCategoryBreakdown = ComputeCategoryBreakdown(ledger,
            FakeFxRepository(CsvTestCurrencies(currencies), CsvTestRates(emptyList()),
                CsvTestSettings(Settings(baseCurrencyCode = "USD")))),
        observeLedgerHealth = ObserveLedgerHealth(ledger),
        categories = CsvTestCategories(emptyList()), currencies = CsvTestCurrencies(currencies),
        moneyFormatter = MoneyFormatter(Locale.US), today = TodayProvider { day },
        savedStateHandle = saved, defaultDispatcher = dispatcher, errors = errors,
        exportTrackingCsv = ExportTrackingCsv(source, RfcCsvEncoder()),
        documentIo = object : DocumentIo {
            override suspend fun write(uri: Uri, block: suspend (OutputStream) -> Unit) =
                error("URI glue belongs to the device test")
            override suspend fun read(uri: Uri, block: suspend (InputStream) -> Unit) =
                error("CSV never imports documents")
        },
    )

    @Test
    fun preflightCapturesVisibleDatesAndWritingKeepsThatArtifactWhenDatesChange() = runTest {
        val source = RecordingSource(listOf(expense(1), expense(2, day.plusDays(1))))
        val vm = vm(source)
        vm.selectRange(day, day)
        vm.prepareExport()
        runCurrent()
        assertEquals(listOf(DateRange(day, day)), source.requestedRanges)
        assertTrue(vm.exportState.value is ReportsExportState.Ready)
        vm.onExportPickerLaunched()
        vm.selectRange(day.plusDays(1), day.plusDays(1))
        val output = ByteArrayOutputStream()
        vm.runCsvWrite { write -> write(output) }
        val expected = ExportTrackingCsv(RecordingSource(listOf(expense(1))), RfcCsvEncoder())(DateRange(day, day))
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), output.toByteArray())
        assertTrue(output.toString("UTF-8").startsWith("\uFEFFdate,"))
        assertTrue(vm.exportState.value is ReportsExportState.Success)
    }

    @Test
    fun missingRatePreflightNeverOffersADocumentOrWritesOutput() = runTest {
        val source = RecordingSource(emptyList()).apply { failure = WalletException.MissingRate("EUR") }
        val vm = vm(source)
        vm.prepareExport()
        runCurrent()
        assertEquals(ReportsExportState.Error(UiMessage.MissingRate("EUR")), vm.exportState.value)
        var writes = 0
        vm.runCsvWrite { writes++ }
        assertEquals(0, writes)
    }

    @Test
    fun directExportRechecksGlobalUnreadableRowsBeforeReadingTheWindow() = runTest {
        val source = RecordingSource(emptyList())
        val ledger = CsvTestLedger(emptyList()).apply { unknown.value = UnreadableRowRef(99, "FUTURE") }
        val vm = vm(source, ledger)
        vm.prepareExport()
        runCurrent()
        assertEquals(emptyList<DateRange>(), source.requestedRanges)
        assertEquals(ReportsExportState.Error(UiMessage.DataUnreadable), vm.exportState.value)
    }

    @Test
    fun cancellingPickerClearsThePreparedArtifactAndNextExportUsesNewDates() = runTest {
        val source = RecordingSource(emptyList())
        val vm = vm(source)
        vm.prepareExport()
        runCurrent()
        vm.onExportPickerLaunched()
        vm.completeExport(null)
        runCurrent()
        assertEquals(ReportsExportState.Idle, vm.exportState.value)
        vm.selectRange(day, day)
        vm.prepareExport()
        runCurrent()
        assertEquals(DateRange(day, day), source.requestedRanges.last())
    }

    @Test
    fun duplicateRequestsDuringPreparationAndThePickerDoNotRebuildTheArtifact() = runTest {
        val source = RecordingSource(emptyList()).apply { blocked = CompletableDeferred() }
        val vm = vm(source)
        vm.prepareExport()
        vm.prepareExport()
        runCurrent()
        assertEquals(1, source.requestedRanges.size)
        source.blocked!!.complete(Unit)
        runCurrent()
        vm.onExportPickerLaunched()
        vm.prepareExport()
        runCurrent()
        assertEquals(1, source.requestedRanges.size)
    }

    @Test
    fun unexpectedPreparationFailureIsReportedAndShowsAGenericMessage() = runTest {
        val failure = IllegalStateException("broken storage")
        val source = RecordingSource(emptyList()).apply { this.failure = failure }
        val errors = RecordingErrorReporter()
        val vm = vm(source, errors = errors)
        vm.prepareExport()
        runCurrent()
        assertEquals(ReportsExportState.Error(UiMessage.Unexpected), vm.exportState.value)
        assertEquals(1, errors.reported.size)
    }

    @Test
    fun runningOutOfMemoryWhilePreparingShowsTheExportFailedMessageInsteadOfCrashing() = runTest {
        val source = RecordingSource(emptyList()).apply { failure = OutOfMemoryError("all-time export") }
        val errors = RecordingErrorReporter()
        val vm = vm(source, errors = errors)
        vm.prepareExport()
        runCurrent()
        assertEquals(ReportsExportState.Error(UiMessage.Unexpected), vm.exportState.value)
        assertEquals(1, errors.reported.size)
        // The failed attempt leaves nothing behind, so a smaller range can be tried at once.
        vm.consumeExportResult()
        source.failure = null
        vm.prepareExport()
        runCurrent()
        assertTrue(vm.exportState.value is ReportsExportState.Ready)
    }

    @Test
    fun cancelledPreparationResetsWithoutReportingAnUnexpectedFailure() = runTest {
        val source = RecordingSource(emptyList()).apply { failure = CancellationException("stopped") }
        val errors = RecordingErrorReporter()
        val vm = vm(source, errors = errors)
        vm.prepareExport()
        runCurrent()
        assertEquals(ReportsExportState.Idle, vm.exportState.value)
        assertEquals(0, errors.reported.size)
    }

    @Test
    fun allTimeExportUsesAPortableFileNameAndClearsTerminalStatus() = runTest {
        val vm = vm(RecordingSource(emptyList()))
        vm.selectPeriod(TrackingPeriod.ALL_TIME)
        vm.prepareExport()
        runCurrent()
        assertEquals(ReportsExportState.Ready("tracking-all-time.csv"), vm.exportState.value)
        vm.onExportPickerLaunched()
        vm.runCsvWrite { write -> write(ByteArrayOutputStream()) }
        vm.consumeExportResult()
        assertEquals(ReportsExportState.Idle, vm.exportState.value)
    }

    @Test
    fun preparationKeepsTheRangeCapturedBeforeItsReadFinishes() = runTest {
        val source = RecordingSource(emptyList()).apply { blocked = CompletableDeferred() }
        val vm = vm(source)
        vm.selectRange(day, day)
        vm.prepareExport()
        runCurrent()
        vm.selectRange(day.plusDays(1), day.plusDays(2))
        source.blocked!!.complete(Unit)
        runCurrent()
        assertEquals(ReportsExportState.Ready("tracking-$day-to-$day.csv"), vm.exportState.value)
        assertEquals(listOf(DateRange(day, day)), source.requestedRanges)
    }

    @Test
    fun documentCloseFailureDoesNotReportSuccessAfterBytesWereWritten() = runTest {
        val errors = RecordingErrorReporter()
        val vm = vm(RecordingSource(listOf(expense(1))), errors = errors)
        vm.prepareExport()
        runCurrent()
        vm.onExportPickerLaunched()
        val output = ByteArrayOutputStream()
        vm.runCsvWrite { write ->
            write(output)
            throw java.io.IOException("close failed")
        }
        assertTrue(output.size() > 0)
        assertEquals(ReportsExportState.Error(UiMessage.Unexpected), vm.exportState.value)
        assertEquals(1, errors.reported.size)
    }

    @Test
    fun writeCancellationClearsArtifactAndPropagatesWithoutReportingFailure() = runTest {
        val errors = RecordingErrorReporter()
        val vm = vm(RecordingSource(emptyList()), errors = errors)
        vm.prepareExport()
        runCurrent()
        vm.onExportPickerLaunched()
        var cancelled = false
        try {
            vm.runCsvWrite { throw CancellationException("stopped") }
        } catch (error: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(ReportsExportState.Idle, vm.exportState.value)
        assertEquals(0, errors.reported.size)
    }

    @Test
    fun aWriteThatHasStartedFinishesTheDocumentWhenTheScreenGoesAway() = runTest {
        val errors = RecordingErrorReporter()
        val vm = vm(RecordingSource(listOf(expense(1))), errors = errors)
        vm.selectRange(day, day)
        vm.prepareExport()
        runCurrent()
        vm.onExportPickerLaunched()
        val opened = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val output = ByteArrayOutputStream()
        val writing = launch {
            vm.runCsvWrite { write ->
                opened.complete(Unit)
                release.await()
                write(output)
            }
        }
        runCurrent()
        assertTrue(opened.isCompleted)
        // Leaving the screen cancels its scope after the document was opened (and truncated).
        writing.cancel()
        release.complete(Unit)
        writing.join()
        val expected = ExportTrackingCsv(RecordingSource(listOf(expense(1))), RfcCsvEncoder())(DateRange(day, day))
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), output.toByteArray())
        assertEquals(ReportsExportState.Success, vm.exportState.value)
        assertEquals(0, errors.reported.size)
    }

    @Test
    fun recreatedViewModelDoesNotRestoreThePreparedCsvFromSavedState() = runTest {
        val saved = SavedStateHandle()
        val first = vm(RecordingSource(emptyList()), saved = saved)
        first.prepareExport()
        runCurrent()
        assertTrue(first.exportState.value is ReportsExportState.Ready)
        val recreated = vm(RecordingSource(emptyList()), saved = saved)
        assertEquals(ReportsExportState.Idle, recreated.exportState.value)
        var writes = 0
        recreated.runCsvWrite { writes++ }
        assertEquals(0, writes)
    }

    @Test
    fun restoredPendingPickerReportsRetryInsteadOfSilentlyIgnoringASelectedDocument() = runTest {
        val saved = SavedStateHandle()
        val first = vm(RecordingSource(emptyList()), saved = saved)
        first.prepareExport()
        runCurrent()
        first.onExportPickerLaunched()
        assertEquals(ReportsExportState.AwaitingDestination, first.exportState.value)
        val recreated = vm(RecordingSource(emptyList()), saved = saved)
        var opened = 0
        recreated.runCsvWrite { opened++ }
        assertEquals(0, opened)
        assertEquals(ReportsExportState.Error(UiMessage.Unexpected), recreated.exportState.value)
        recreated.consumeExportResult()
        recreated.selectRange(day, day)
        recreated.prepareExport()
        runCurrent()
        assertEquals(ReportsExportState.Ready("tracking-$day-to-$day.csv"), recreated.exportState.value)
    }

    @Test
    fun recreationAfterThePickerResultDoesNotWaitForAnotherExternalCallback() = runTest {
        val saved = SavedStateHandle()
        val first = vm(RecordingSource(emptyList()), saved = saved)
        first.prepareExport()
        runCurrent()
        first.onExportPickerLaunched()
        val release = CompletableDeferred<Unit>()
        val writing = launch {
            first.runCsvWrite { write ->
                release.await()
                write(ByteArrayOutputStream())
            }
        }
        runCurrent()
        assertEquals(ReportsExportState.Writing, first.exportState.value)
        try {
            val recreated = vm(RecordingSource(emptyList()), saved = saved)
            assertEquals(ReportsExportState.Idle, recreated.exportState.value)
        } finally {
            release.complete(Unit)
        }
        writing.join()
        assertEquals(ReportsExportState.Success, first.exportState.value)
    }

    @Test
    fun restoredPendingPickerCancellationClearsWithoutReportingAnError() = runTest {
        val saved = SavedStateHandle()
        val first = vm(RecordingSource(emptyList()), saved = saved)
        first.prepareExport()
        runCurrent()
        first.onExportPickerLaunched()
        val recreated = vm(RecordingSource(emptyList()), saved = saved)
        recreated.completeExport(null)
        assertEquals(ReportsExportState.Idle, recreated.exportState.value)
        recreated.prepareExport()
        runCurrent()
        assertTrue(recreated.exportState.value is ReportsExportState.Ready)
    }

    @Test
    fun pickerLaunchFailureClearsArtifactAndIsReported() = runTest {
        val errors = RecordingErrorReporter()
        val vm = vm(RecordingSource(emptyList()), errors = errors)
        vm.prepareExport()
        runCurrent()
        vm.onExportPickerLaunched()
        vm.onExportPickerFailed(IllegalStateException("no picker"))
        assertEquals(ReportsExportState.Error(UiMessage.Unexpected), vm.exportState.value)
        assertEquals(1, errors.reported.size)
        vm.consumeExportResult()
        assertEquals(ReportsExportState.Idle, vm.exportState.value)
    }

    @Test
    fun stalePickerCallbacksDoNotInterruptPreparationOrDiscardItsReadyArtifact() = runTest {
        val errors = RecordingErrorReporter()
        val source = RecordingSource(emptyList()).apply { blocked = CompletableDeferred() }
        val vm = vm(source, errors = errors)
        vm.prepareExport()
        runCurrent()
        assertEquals(ReportsExportState.Preparing, vm.exportState.value)
        vm.onExportPickerLaunched()
        vm.onExportPickerFailed(IllegalStateException("stale callback"))
        vm.completeExport(null)
        vm.consumeExportResult()
        var opened = 0
        vm.runCsvWrite { opened++ }
        assertEquals(0, opened)
        assertEquals(ReportsExportState.Preparing, vm.exportState.value)
        source.blocked!!.complete(Unit)
        runCurrent()
        assertTrue(vm.exportState.value is ReportsExportState.Ready)
        vm.onExportPickerLaunched()
        vm.onExportPickerLaunched()
        assertEquals(ReportsExportState.AwaitingDestination, vm.exportState.value)
        vm.runCsvWrite { write -> write(ByteArrayOutputStream()) }
        assertEquals(ReportsExportState.Success, vm.exportState.value)
        assertEquals(0, errors.reported.size)
    }

    @Test
    fun callbacksAfterACompletedExportPreserveItsResultUntilItIsConsumed() = runTest {
        val errors = RecordingErrorReporter()
        val vm = vm(RecordingSource(emptyList()), errors = errors)
        vm.prepareExport()
        runCurrent()
        vm.onExportPickerLaunched()
        vm.runCsvWrite { write -> write(ByteArrayOutputStream()) }
        vm.onExportPickerLaunched()
        vm.onExportPickerFailed(IllegalStateException("late picker"))
        vm.completeExport(null)
        assertEquals(ReportsExportState.Success, vm.exportState.value)
        vm.consumeExportResult()
        assertEquals(ReportsExportState.Idle, vm.exportState.value)
        vm.consumeExportResult()
        vm.onExportPickerLaunched()
        vm.onExportPickerFailed(IllegalStateException("unrelated picker"))
        vm.completeExport(null)
        assertEquals(ReportsExportState.Idle, vm.exportState.value)
        assertEquals(0, errors.reported.size)
        vm.prepareExport()
        runCurrent()
        assertTrue(vm.exportState.value is ReportsExportState.Ready)
    }

    @Test
    fun writeFailureIsReportedAndClearsTheArtifact() = runTest {
        val vm = vm(RecordingSource(emptyList()))
        vm.prepareExport()
        runCurrent()
        vm.onExportPickerLaunched()
        vm.runCsvWrite { throw java.io.IOException("disk full") }
        assertEquals(ReportsExportState.Error(UiMessage.Unexpected), vm.exportState.value)
        var writes = 0
        vm.runCsvWrite { writes++ }
        assertEquals(0, writes)
    }
}

private class CsvTestLedger(initial: List<Transaction>) : LedgerQueries {
    private val items = MutableStateFlow(initial)

    override fun observeCategoryLegs(range: DateRange): Flow<List<CategoryLeg>> =
        items.map { all -> all.filter { it.date in range }.flatMap(::categoryLegsOf) }

    override suspend fun fillMissingRate(code: String, rate: BigDecimal): Int {
        var changed = 0
        items.value = items.value.map { tx ->
            when {
                tx is Transaction.Income && tx.rateToBase == null && tx.amount.currencyCode == code ->
                    tx.copy(rateToBase = rate).also { changed++ }
                tx is Transaction.Expense && tx.rateToBase == null && tx.amount.currencyCode == code ->
                    tx.copy(rateToBase = rate).also { changed++ }
                else -> tx
            }
        }
        return changed
    }

    // Health is independent of the visible report window.
    var unknown = MutableStateFlow<UnreadableRowRef?>(null)
    override fun observeUnknownKind(known: Set<String>): Flow<UnreadableRowRef?> = unknown

    // Reports read Tracking and the ledger's health only; failing here keeps a drifting test from passing quietly.
    override fun observeAccountLegs(): Flow<List<AccountLeg>> = error("reports do not read balances")
    override suspend fun isAccountInUse(accountId: Long): Boolean = error("reports do not check account use")
    override fun observeAccountsInUse(): Flow<Set<Long>> = error("reports do not check account use")
    override fun observeCategoryUsage(): Flow<Map<Long, Int>> = error("reports do not read category usage")
    override suspend fun debtLegs(debtIds: Set<Long>): List<DebtLeg> = emptyList()
    override fun observeDebtLegs(): Flow<List<DebtLeg>> = flowOf(emptyList())
    override suspend fun distinctLegRates(): List<LegRate> = error("reports do not read leg rates")

    private fun categoryLegsOf(tx: Transaction): List<CategoryLeg> {
        if (tx is Transaction.Transfer) return emptyList()
        val parts = tx.toParts()
        return parts.legs.mapNotNull { leg ->
            val target = leg.target as? LegTarget.Category ?: return@mapNotNull null
            CategoryLeg(
                entryId = tx.id, date = tx.date, categoryId = target.id, subCategoryId = target.subId,
                entryKind = parts.header.kind, role = leg.role,
                amount = leg.amount, rateToBase = leg.rateToBase,
            )
        }
    }


}

private class CsvTestCategories(initial: List<Category>) : CategoryRepository {
    private val items = MutableStateFlow(initial)

    /** Every read of the categories fails, as one over a broken table would. */
    var failing = false

    override fun observeAll(): Flow<List<Category>> =
        flow { if (failing) throw IllegalStateException("categories could not be read") else emitAll(items) }
    override suspend fun findById(id: Long): Category? = items.value.firstOrNull { it.id == id }

    // Reports only read categories — managing them is a different screen. Failing here rather than
    // returning something plausible keeps a drifting test from passing quietly.
    override suspend fun upsert(category: Category): Long = error("reports do not write categories")

    override suspend fun deleteAndReassign(categoryId: Long, kind: CategoryKind, fallbackKey: String): Int =
        error("reports do not delete categories")

    override suspend fun bucketFor(kind: CategoryKind): Long =
        error("reports do not create buckets")
}

private class CsvTestCurrencies(initial: List<Currency>) : CurrencyRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Currency>> = items
    override suspend fun findByCode(code: String): Currency? = items.value.firstOrNull { it.code == code }
}

private class CsvTestRates(initial: List<ExchangeRate>) : RateRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<ExchangeRate>> = items
    override suspend fun findByCode(code: String): ExchangeRate? = items.value.firstOrNull { it.currencyCode == code }
    override suspend fun upsert(rate: ExchangeRate) {
        items.value = items.value.filterNot { it.currencyCode == rate.currencyCode } + rate
    }
}

private class CsvTestSettings(initial: Settings) : SettingsRepository {
    private val state = MutableStateFlow<Settings?>(initial)
    override fun observe(): Flow<Settings> = state.filterNotNull()
    override fun observeOrNull(): Flow<Settings?> = state
    override suspend fun get(): Settings = state.value ?: error("settings not initialized")
    override suspend fun save(settings: Settings) { state.value = settings }
}
