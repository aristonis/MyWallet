package org.aristonis.mywallet.data.backup

import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.TransactionEntity
import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The pure snapshot→backup and text→validated-backup functions must survive a full round-trip
 * (every table populated, money strings byte-for-byte) and must reject corrupt or newer-format
 * input as typed domain errors rather than defaulting silently — restore safety depends on that.
 */
class BackupAssemblerTest {

    private val currencies = listOf(
        CurrencyEntity(code = "USD", symbol = "$", decimalPlaces = 2),
        CurrencyEntity(code = "EUR", symbol = "€", decimalPlaces = 2),
    )
    private val categories = listOf(
        CategoryEntity(id = 11, name = "Food", kind = "EXPENSE"),
        // a sub-category (parentId set) exercises the nullable parent reference
        CategoryEntity(id = 21, name = "Groceries", kind = "EXPENSE", parentId = 11),
    )
    private val accounts = listOf(
        AccountEntity(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalanceAmount = "100.50"),
        AccountEntity(id = 2, name = "Bank", typeKey = "card", currencyCode = "EUR", openingBalanceAmount = "0.00"),
    )
    private val rates = listOf(RateEntity(currencyCode = "EUR", rateToBase = "1.086956"))
    private val transactions = listOf(
        TransactionEntity(
            id = 1, type = "INCOME", date = LocalDate.of(2026, 1, 5),
            primaryAccountId = 1, primaryAmount = "1234.00", primaryCurrency = "USD", categoryId = 11,
        ),
        TransactionEntity(
            id = 2, type = "EXPENSE", date = LocalDate.of(2026, 1, 6), note = "lunch",
            primaryAccountId = 1, primaryAmount = "12.99", primaryCurrency = "USD",
            categoryId = 11, subCategoryId = 21,
        ),
        // a transfer carries the secondary leg + the applied rate
        TransactionEntity(
            id = 3, type = "TRANSFER", date = LocalDate.of(2026, 1, 7),
            primaryAccountId = 1, primaryAmount = "20.000", primaryCurrency = "USD",
            secondaryAccountId = 2, secondaryAmount = "18.40", secondaryCurrency = "EUR",
            rateUsed = "0.920000",
        ),
    )
    private val settings = SettingsEntity(baseCurrencyCode = "USD", theme = "SYSTEM", schemaVersion = 4)

    private fun build(settingsRow: SettingsEntity? = settings) =
        buildBackup(accounts, transactions, categories, currencies, rates, settingsRow)

    @Test
    fun buildBackup_roundTripsThroughEncodeAndValidatedDecode() {
        val built = build()
        assertEquals(built, decodeValidated(BackupCodec.encode(built)))
    }

    @Test
    fun buildBackup_withNullSettings_producesANullSettingsBlock() {
        assertNull(build(settingsRow = null).settings)
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsABackupWithoutSettings() {
        // A wallet backup always carries settings (the base currency); a settings-less one isn't a
        // restorable wallet, so it's refused rather than restored into a broken half-state.
        decodeValidated(BackupCodec.encode(build(settingsRow = null)))
    }

    @Test
    fun buildBackup_stampsTheCurrentVersion() {
        assertEquals(CURRENT_BACKUP_VERSION, build().version)
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsCorruptJson() {
        decodeValidated("{ not valid json")
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsMalformedDate() {
        // A well-formed JSON document whose date field can't be parsed — the common shape of a file
        // truncated mid-write — must still surface as BackupInvalid, not an uncaught parse error.
        val withBadDate = BackupCodec.encode(build()).replace("2026-01-05", "2026-13-45")
        decodeValidated(withBadDate)
    }

    @Test(expected = WalletException.BackupVersionUnsupported::class)
    fun decodeValidated_rejectsUnsupportedVersion() {
        val newerFormat = BackupCodec.encode(build().copy(version = 2))
        decodeValidated(newerFormat)
    }

    @Test
    fun decodeValidated_acceptsValidCurrentVersion_returnsEqual() {
        val built = build()
        assertEquals(built, decodeValidated(BackupCodec.encode(built)))
    }
}
