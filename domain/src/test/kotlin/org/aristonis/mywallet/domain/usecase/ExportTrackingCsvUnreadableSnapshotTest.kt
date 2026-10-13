package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.CsvRow
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CsvCategoryLeg
import org.aristonis.mywallet.domain.port.CsvEncoder
import org.aristonis.mywallet.domain.port.CsvExportSnapshot
import org.aristonis.mywallet.domain.port.CsvExportSource
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** An inconsistent export snapshot cannot reach the encoder and produce a partial file. */
class ExportTrackingCsvUnreadableSnapshotTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val range = DateRange(day, day)
    private val fx = fakeFx(listOf(Currency("USD", "$", 2)))

    @Test fun aCategoryLegWithoutItsEntryHeaderRefusesBeforeEncoding() = runTest {
        val entry = Transaction.Expense(7, 1, Money.of("10", "USD"), 2, date = day)
        val legs = FakeLedgerQueries(FakeEntryRepository(listOf(entry))).observeCategoryLegs(range).first()
        val snapshot = CsvExportSnapshot(legs.map { CsvCategoryLeg(it, 0) }, emptyList(), fx.currentFx(),
            emptyList(), emptyList(), emptyList())
        assertUnreadableWithoutEncoding(snapshot, "EXPENSE")
    }

    @Test fun aDeductionPositionWithoutItsRecordedLineRefusesBeforeEncoding() = runTest {
        val line = DeductionLine(1, 0, "Tax", DeductionMode.FIXED, BigDecimal.TEN, "USD",
            DeductionTiming.NOW, 2, Money.of("10", "USD"))
        val entry = Transaction.Income(7, 1, Money.of("100", "USD"), 1, date = day, deductions = listOf(line))
        val leg = FakeLedgerQueries(FakeEntryRepository(listOf(entry))).observeCategoryLegs(range).first()
            .single { it.role == LegRole.DEDUCTION }
        val snapshot = CsvExportSnapshot(listOf(CsvCategoryLeg(leg, 1)), listOf(entry), fx.currentFx(),
            emptyList(), emptyList(), emptyList())
        assertUnreadableWithoutEncoding(snapshot, "INCOME")
    }

    private suspend fun assertUnreadableWithoutEncoding(snapshot: CsvExportSnapshot, kind: String) {
        var encoded = false
        val encoder = object : CsvEncoder {
            override fun encode(rows: List<CsvRow>): String {
                encoded = true
                return "unexpected output"
            }
        }
        val source = object : CsvExportSource {
            override suspend fun read(range: DateRange) = snapshot
        }
        val failure = runCatching { ExportTrackingCsv(source, encoder)(range) }.exceptionOrNull()
        assertTrue(failure is WalletException.UnreadableRow)
        assertEquals(7L, (failure as WalletException.UnreadableRow).id)
        assertEquals(kind, failure.kind)
        assertFalse(encoded)
    }
}
