package org.aristonis.mywallet.data.csv

import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CsvRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

class RfcCsvEncoderTest {
    private fun row(note: String = "") = CsvRow(
        LocalDate.of(2026, 10, 12), 1, "REFUND", "MAIN", "Food", "", CategoryKind.EXPENSE,
        BigDecimal("-1234.5"), "USD", BigDecimal("1E-7"), BigDecimal("-1234.5"), "Cash", "", note,
    )

    @Test
    fun headerOrderBomAndCrLfArePortable() {
        assertEquals("\uFEFFdate,entry_id,kind,line,category,sub_category,side,amount,currency,rate_to_base," +
            "amount_in_base,account,counterparty,note\r\n", RfcCsvEncoder().encode(emptyList()))
    }

    @Test
    fun dangerousTextGetsAPrefixWhileNegativeNumericCellsRemainNumbers() {
        for (prefix in listOf("=", "+", "-", "@", "\t", "\r")) {
            val csv = RfcCsvEncoder().encode(listOf(row(prefix + "value")))
            assertTrue(csv.contains("'" + prefix + "value"))
            assertTrue(csv.contains(",-1234.5,USD,0.0000001,-1234.5,"))
        }
    }

    @Test
    fun commasQuotesAndNewlinesUseRfcQuoting() {
        val csv = RfcCsvEncoder().encode(listOf(row("one, \"two\"\r\nthree")))
        assertTrue(csv.endsWith("\"one, \"\"two\"\"\r\nthree\"\r\n"))
    }

    @Test
    fun numbersUsePlainAsciiDecimalsUnderGermanAndArabicLocales() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.forLanguageTag("ar-SY"))) {
                Locale.setDefault(locale)
                assertTrue(RfcCsvEncoder().encode(listOf(row())).contains(",-1234.5,USD,0.0000001,-1234.5,"))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun everyTextColumnIsProtectedAndQuotedIndependently() {
        val csv = RfcCsvEncoder().encode(listOf(row().copy(
            kind = "=kind", line = "+line", category = "-category", subCategory = "@sub",
            currency = "=code", account = "\taccount", counterparty = "\rperson", note = "@note",
        )))
        for (text in listOf("'=kind", "'+line", "'-category", "'@sub", "'=code", "'\taccount", "'\rperson", "'@note")) {
            assertTrue(csv.contains(text))
        }
    }
}
