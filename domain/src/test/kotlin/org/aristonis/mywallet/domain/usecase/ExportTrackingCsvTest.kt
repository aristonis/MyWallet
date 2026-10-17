package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.CsvRow
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CsvCategoryLeg
import org.aristonis.mywallet.domain.port.CsvEncoder
import org.aristonis.mywallet.domain.port.CsvExportSource
import org.aristonis.mywallet.domain.port.CsvExportSnapshot
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ExportTrackingCsvTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val range = DateRange(day, day)
    private val fx = fakeFx(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)))

    private class RecordingEncoder : CsvEncoder {
        var rows: List<CsvRow>? = null
        override fun encode(rows: List<CsvRow>): String {
            this.rows = rows
            return "encoded"
        }
    }

    private fun export(entries: FakeEntryRepository, encoder: RecordingEncoder) = ExportTrackingCsv(
        object : CsvExportSource {
            override suspend fun read(range: DateRange) = CsvExportSnapshot(
                FakeLedgerQueries(entries).observeCategoryLegs(range).first().map { CsvCategoryLeg(it, 0) },
                entries.observeBetween(range).first(), fx.currentFx(), emptyList(), emptyList(), emptyList(),
            )
        }, encoder,
    )

    private fun expense(id: Long, amount: String, currency: String = "USD", rate: String? = null) =
        Transaction.Expense(id, 1, Money.of(amount, currency), 7, date = day,
            rateToBase = rate?.let(::BigDecimal))

    @Test
    fun rowsSumToTrackingIncludingDeductionsFeesInterestAndRefunds() = runTest {
        val deduction = DeductionLine(1, 0, "Tax", DeductionMode.PERCENT, BigDecimal("10"), null,
            DeductionTiming.NOW, 7, Money.of("10", "USD"))
        val entries = FakeEntryRepository(listOf(
            Transaction.Income(1, 1, Money.of("100", "USD"), 1, date = day, deductions = listOf(deduction)),
            expense(2, "30").copy(fee = Money.of("1", "USD"), feeCategoryId = 8),
            Transaction.Refund(3, 1, Money.of("5", "USD"), 7, date = day),
            Transaction.Repayment(4, DebtSide.I_OWE, 1, 1, Money.of("12", "USD"), Money.of("10", "USD"),
                interest = Money.of("2", "USD"), interestCategoryId = 9, date = day),
            expense(5, "0.5", "EUR", "2.99999999999999999999999997"),
            expense(6, "999").copy(date = day.minusDays(1)),
        ))
        val encoder = RecordingEncoder()
        assertEquals("encoded", export(entries, encoder)(range))
        val rows = requireNotNull(encoder.rows)
        val tracking = (ComputeCategoryBreakdown(FakeLedgerQueries(entries), fx)(range).first()
            as CategoryBreakdownResult.Resolved).breakdown.summary
        val sides = listOf(CategoryKind.INCOME to tracking.income, CategoryKind.EXPENSE to tracking.expense)
        for ((side, total) in sides) {
            val sum = rows.filter { it.side == side }
                .fold(BigDecimal.ZERO) { acc, row -> acc + row.amountInBase }
            assertEquals(0, sum.compareTo(total.amount))
        }
        assertEquals(7, rows.size)
        assertTrue(rows.any { it.line == "DEDUCTION" && it.kind == "INCOME" })
        assertTrue(rows.any { it.line == "FEE" && it.kind == "EXPENSE" })
        assertTrue(rows.any { it.line == "INTEREST_PAID" && it.kind == "REPAYMENT" })
        assertEquals(0, rows.single { it.kind == "REFUND" }.amount.compareTo(BigDecimal("-5")))
        assertEquals(0, rows.single { it.currency == "EUR" }.amountInBase.compareTo(BigDecimal("1.50")))
    }

    @Test
    fun missingRateRefusesBeforeEncoderSeesAnyRows() = runTest {
        val encoder = RecordingEncoder()
        val failure = runCatching {
            val entries = FakeEntryRepository(listOf(expense(1, "10"), expense(2, "1", "EUR")))
            export(entries, encoder)(range)
        }.exceptionOrNull()
        assertTrue(failure is WalletException.MissingRate)
        assertEquals("EUR", (failure as WalletException.MissingRate).code)
        assertNull(encoder.rows)
    }

    @Test
    fun baseCurrencyTypedFractionIsNotRoundedAgain() = runTest {
        val encoder = RecordingEncoder()
        export(FakeEntryRepository(listOf(expense(1, "1.005"))), encoder)(range)
        assertEquals(0, requireNotNull(encoder.rows).single().amountInBase.compareTo(BigDecimal("1.005")))
    }

    @Test
    fun emptyWindowStillEncodesAnEmptyList() = runTest {
        val encoder = RecordingEncoder()
        export(FakeEntryRepository(listOf(expense(1, "10").copy(date = day.minusDays(1)))), encoder)(range)
        assertEquals(emptyList<CsvRow>(), encoder.rows)
    }

    @Test
    fun metadataNamesAndNotesAreReadFromTheProvidedSnapshot() = runTest {
        val entry = expense(1, "4").copy(subCategoryId = 8, note = "receipt")
        val entries = FakeEntryRepository(listOf(entry))
        val encoder = RecordingEncoder()
        val snapshot = CsvExportSnapshot(
            FakeLedgerQueries(entries).observeCategoryLegs(range).first().map { CsvCategoryLeg(it, 0) },
            listOf(entry), fx.currentFx(),
            listOf(Category(7, "Food", CategoryKind.EXPENSE), Category(8, "Lunch", CategoryKind.EXPENSE, 7)),
            listOf(Account(1, "Cash", "cash", "USD", Money.zero("USD"))), emptyList(),
        )
        ExportTrackingCsv(object : CsvExportSource {
            override suspend fun read(range: DateRange) = snapshot
        }, encoder)(range)
        val row = requireNotNull(encoder.rows).single()
        assertEquals("Food", row.category)
        assertEquals("Lunch", row.subCategory)
        assertEquals("Cash", row.account)
        assertEquals("receipt", row.note)
    }

    @Test
    fun unreadableRoleIsNotHiddenByAMissingRate() = runTest {
        val entries = FakeEntryRepository(listOf(expense(1, "4", "EUR")))
        val leg = FakeLedgerQueries(entries).observeCategoryLegs(range).first().single()
        val snapshot = CsvExportSnapshot(listOf(CsvCategoryLeg(leg.copy(entryKind = "UNKNOWN"), 0)), emptyList(),
            fx.currentFx(), emptyList(), emptyList(), emptyList())
        val encoder = RecordingEncoder()
        val failure = runCatching {
            ExportTrackingCsv(object : CsvExportSource {
                override suspend fun read(range: DateRange) = snapshot
            }, encoder)(range)
        }.exceptionOrNull()
        assertTrue(failure is WalletException.UnreadableRow)
        assertNull(encoder.rows)
    }

    @Test
    fun sourceCancellationPropagatesWithoutEncoding() = runTest {
        val encoder = RecordingEncoder()
        val failure = runCatching {
            ExportTrackingCsv(object : CsvExportSource {
                override suspend fun read(range: DateRange): CsvExportSnapshot = throw CancellationException("stopped")
            }, encoder)(range)
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertNull(encoder.rows)
    }

    @Test
    fun equalDeductionAmountsInOneCategoryKeepTheirOwnRecipients() = runTest {
        val lines = listOf(
            DeductionLine(1, 0, "Tax", DeductionMode.FIXED, BigDecimal("10"), "USD",
                DeductionTiming.LATER, 7, Money.of("10", "USD"), 1),
            DeductionLine(2, 1, "Family", DeductionMode.FIXED, BigDecimal("10"), "USD",
                DeductionTiming.LATER, 7, Money.of("10", "USD"), 2),
            DeductionLine(3, 2, "Paid tax", DeductionMode.FIXED, BigDecimal("10"), "USD",
                DeductionTiming.NOW, 7, Money.of("10", "USD")),
        )
        val entry = Transaction.Income(1, 1, Money.of("100", "USD"), 1, date = day, deductions = lines)
        val entries = FakeEntryRepository(listOf(entry))
        val projected = FakeLedgerQueries(entries).observeCategoryLegs(range).first()
        val wrapped = projected.mapIndexed { index, leg -> CsvCategoryLeg(leg, if (index == 0) 0 else index - 1) }
        val snapshot = CsvExportSnapshot(wrapped, listOf(entry), fx.currentFx(), emptyList(), emptyList(),
            listOf(Debt(1, "Tax office", DebtSide.I_OWE, "USD"), Debt(2, "Family", DebtSide.I_OWE, "USD")))
        val encoder = RecordingEncoder()
        ExportTrackingCsv(object : CsvExportSource {
            override suspend fun read(range: DateRange) = snapshot
        }, encoder)(range)
        val rows = requireNotNull(encoder.rows)
        assertEquals(listOf("Tax office", "Family", "Paid tax"),
            rows.filter { it.line == "DEDUCTION" }.map { it.counterparty })
        assertEquals("", rows.single { it.line == "MAIN" }.counterparty)
    }

    @Test fun paidLaterExpenseNamesThePersonOnlyOnTheOwedRow() = runTest {
        val entry = expense(1, "1000").copy(owed = Money.of("700", "USD"), owedDebtId = 1,
            fee = Money.of("3", "USD"), feeCategoryId = 8)
        val rows = exportWithPerson(entry)
        assertEquals("", rows.single { it.line == "MAIN" }.counterparty)
        assertEquals("", rows.single { it.line == "FEE" }.counterparty)
        assertEquals("Ali", rows.single { it.line == "PAID_LATER" }.counterparty)
        assertEquals(0, rows.sumOf { it.amount }.compareTo(BigDecimal("1003")))
    }

    @Test fun fullyOwedExpenseOmitsZeroPaidNowRow() = runTest {
        val rows = exportWithPerson(expense(1, "1000").copy(
            owed = Money.of("1000", "USD"), owedDebtId = 1))
        assertEquals(listOf("PAID_LATER"), rows.map { it.line })
        assertEquals("Ali", rows.single().counterparty)
    }

    @Test fun incomeFeeExportsAsExpenseWithoutAPerson() = runTest {
        val rows = exportWithPerson(Transaction.Income(1, 1, Money.of("1000", "USD"), 1,
            date = day, fee = Money.of("20", "USD"), feeCategoryId = 8))
        val fee = rows.single { it.line == "FEE" }
        assertEquals(CategoryKind.EXPENSE, fee.side)
        assertEquals("", fee.counterparty)
        assertEquals(0, fee.amount.compareTo(BigDecimal("20")))
    }

    private suspend fun exportWithPerson(entry: Transaction): List<CsvRow> {
        val entries = FakeEntryRepository(listOf(entry))
        val snapshot = CsvExportSnapshot(
            FakeLedgerQueries(entries).observeCategoryLegs(range).first().map { CsvCategoryLeg(it, 0) },
            listOf(entry), fx.currentFx(), emptyList(), emptyList(),
            listOf(Debt(1, "Ali", DebtSide.I_OWE, "USD")),
        )
        val encoder = RecordingEncoder()
        ExportTrackingCsv(object : CsvExportSource {
            override suspend fun read(range: DateRange) = snapshot
        }, encoder)(range)
        return requireNotNull(encoder.rows)
    }
}
