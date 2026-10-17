package org.aristonis.mywallet.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.data.csv.RfcCsvEncoder
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CsvRow
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ArabicLocaleExportTest {
    @Test
    fun arabicRuntimeLocalesKeepCsvAndBackupBytesIdenticalToEnglish() {
        val backup = BackupCodec.validate(BackupV2Samples.versionThreeWallet())
        val rows = listOf(CsvRow(
            LocalDate.of(2026, 10, 17), 1234, "EXPENSE", "MAIN", "طعام", "", CategoryKind.EXPENSE,
            BigDecimal("1234.50"), "USD", BigDecimal("0.000001"), BigDecimal("0.00123450"),
            "نقد", "أحمد", "ملاحظة",
        ))
        synchronized(Locale::class.java) {
            val original = Locale.getDefault()
            try {
                fun backupBytes(): ByteArray = ByteArrayOutputStream().use { output ->
                    BackupCodec.encode(backup, output)
                    output.toByteArray()
                }
                Locale.setDefault(Locale.ENGLISH)
                val expectedBackup = backupBytes()
                val expectedCsv = RfcCsvEncoder().encode(rows).toByteArray(Charsets.UTF_8)
                for (tag in listOf("ar", "ar-u-nu-latn")) {
                    Locale.setDefault(Locale.forLanguageTag(tag))
                    assertArrayEquals(tag, expectedBackup, backupBytes())
                    assertArrayEquals(tag, expectedCsv, RfcCsvEncoder().encode(rows).toByteArray(Charsets.UTF_8))
                }
            } finally {
                Locale.setDefault(original)
            }
        }
    }
}
