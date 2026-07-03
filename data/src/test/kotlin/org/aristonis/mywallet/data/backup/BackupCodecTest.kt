package org.aristonis.mywallet.data.backup

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The codec must survive a full round-trip (encode → decode reproduces the original exactly,
 * money strings byte-for-byte) and must reject corrupt or partial input rather than silently
 * defaulting — restore validation depends on that rejection.
 */
class BackupCodecTest {

    private fun sampleBackup(): WalletBackup = WalletBackup(
        version = 1,
        settings = SettingsDto(baseCurrencyCode = "USD", theme = "SYSTEM", schemaVersion = 4),
        accounts = listOf(
            AccountDto(
                id = 1,
                name = "Cash",
                typeKey = "cash",
                currencyCode = "USD",
                openingBalanceAmount = "100.50",
                archived = false,
                sortOrder = 0,
            ),
        ),
        transactions = listOf(
            TransactionDto(
                id = 1, type = "INCOME", date = LocalDate.of(2026, 1, 5),
                primaryAccountId = 1, primaryAmount = "1234.00", primaryCurrency = "USD",
                categoryId = 10,
            ),
            TransactionDto(
                id = 2, type = "EXPENSE", date = LocalDate.of(2026, 1, 6), note = "lunch",
                primaryAccountId = 1, primaryAmount = "12.99", primaryCurrency = "USD",
                categoryId = 11, subCategoryId = 21,
            ),
            TransactionDto(
                id = 3, type = "TRANSFER", date = LocalDate.of(2026, 1, 7),
                primaryAccountId = 1, primaryAmount = "20.000", primaryCurrency = "USD",
                secondaryAccountId = 2, secondaryAmount = "18.40", secondaryCurrency = "EUR",
                rateUsed = "0.920000",
            ),
        ),
        categories = listOf(
            CategoryDto(id = 11, name = "Food", kind = "EXPENSE"),
            CategoryDto(id = 21, name = "Groceries", kind = "EXPENSE", parentId = 11),
        ),
        currencies = listOf(
            CurrencyDto(code = "USD", symbol = "$", decimalPlaces = 2),
            CurrencyDto(code = "EUR", symbol = "€", decimalPlaces = 2),
        ),
        rates = listOf(
            RateDto(currencyCode = "EUR", rateToBase = "1.086956"),
        ),
    )

    @Test
    fun roundTrip_reproducesOriginalExactly() {
        val original = sampleBackup()
        val decoded = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun encode_writesVersionField() {
        val json = BackupCodec.encode(sampleBackup())
        assertTrue("version must be present in the encoded JSON", json.contains("\"version\""))
    }

    @Test(expected = SerializationException::class)
    fun decode_rejectsMalformedJson() {
        BackupCodec.decode("{ not valid json")
    }

    @Test(expected = SerializationException::class)
    fun decode_rejectsMissingRequiredFields() {
        BackupCodec.decode("{}")
    }
}
