package org.aristonis.mywallet.data.csv

import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CsvRow
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

class RfcCsvEncoderLocaleTest {
    @Test
    fun arabicAndArabicWithLatinDigitsProduceTheExactEnglishCsvBytes() {
        val rows = listOf(CsvRow(
            LocalDate.of(2026, 10, 17), 1234, "INCOME", "MAIN", "راتب", "", CategoryKind.INCOME,
            BigDecimal("1234.50"), "USD", BigDecimal("0.000001"), BigDecimal("0.00123450"),
            "نقد", "أحمد", "ملاحظة, \"نص\"",
        ))
        synchronized(Locale::class.java) {
            val original = Locale.getDefault()
            try {
                Locale.setDefault(Locale.ENGLISH)
                val expected = RfcCsvEncoder().encode(rows).toByteArray(Charsets.UTF_8)
                for (tag in listOf("ar", "ar-u-nu-latn")) {
                    Locale.setDefault(Locale.forLanguageTag(tag))
                    val actual = RfcCsvEncoder().encode(rows).toByteArray(Charsets.UTF_8)
                    assertArrayEquals(tag, expected, actual)
                }
            } finally {
                Locale.setDefault(original)
            }
        }
    }
}
