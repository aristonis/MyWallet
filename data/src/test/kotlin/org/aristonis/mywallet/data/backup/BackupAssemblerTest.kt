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
import org.junit.Assert.assertTrue
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

    @Test
    fun buildBackup_carriesTheSystemKeyOfAnAppOwnedBucket() {
        // Identity of the fallback bucket is the key, not the display name, so a backup that dropped
        // it would restore the bucket as an ordinary category the user could then delete.
        val withBucket = buildBackup(
            accounts,
            transactions,
            categories + CategoryEntity(
                id = 99, name = "Uncategorized", kind = "EXPENSE", systemKey = "UNCATEGORIZED_EXPENSE",
            ),
            currencies,
            rates,
            settings,
        )
        val restored = decodeValidated(BackupCodec.encode(withBucket))

        assertEquals("UNCATEGORIZED_EXPENSE", restored.categories.first { it.id == 99L }.systemKey)
        assertNull("a user category must not gain a key", restored.categories.first { it.id == 11L }.systemKey)
    }

    @Test
    fun decodeValidated_acceptsABackupWrittenBeforeSystemKeysExisted() {
        // Older files simply have no such field; they must still restore, with every category read
        // back as user-owned rather than the decode failing on a missing key.
        // The document is pretty-printed, so the field is stripped by pattern rather than by literal
        // text; it is written last in the object, so the comma taken with it is always the preceding one.
        val withoutTheField = BackupCodec.encode(build())
            .replace(Regex(",\\s*\"systemKey\"\\s*:\\s*null"), "")
        val restored = decodeValidated(withoutTheField)

        assertTrue("the field should be gone from the document", !withoutTheField.contains("systemKey"))
        assertTrue("every category reads back as user-owned", restored.categories.all { it.systemKey == null })
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsATransactionNamingACategoryTheBackupDoesNotCarry() {
        // Transactions have a foreign key on their sub-category, so this would otherwise abort the
        // restore part-way with a raw SQLite error the user can do nothing with.
        val dangling = build().let { b ->
            b.copy(transactions = b.transactions.map { if (it.id == 2L) it.copy(subCategoryId = 4242) else it })
        }
        decodeValidated(BackupCodec.encode(dangling))
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsATransactionWhoseMainCategoryIsMissing() {
        val dangling = build().let { b ->
            b.copy(transactions = b.transactions.map { if (it.id == 1L) it.copy(categoryId = 4242) else it })
        }
        decodeValidated(BackupCodec.encode(dangling))
    }

    @Test
    fun decodeValidated_acceptsATransferWhichNamesNoCategoryAtAll() {
        // Transfers are never categorized, so null references must not be mistaken for dangling ones.
        val transfersOnly = build().let { b -> b.copy(transactions = b.transactions.filter { it.type == "TRANSFER" }) }

        assertEquals(1, decodeValidated(BackupCodec.encode(transfersOnly)).transactions.size)
    }

    @Test
    fun aBackupFromANewerBuildSaysSoEvenWithFieldsThisBuildDoesNotKnow() {
        val newer = BackupCodec.encode(build())
            .replaceFirst(Regex("\"version\"\\s*:\\s*1"), "\"version\": 2")
            .replaceFirst("{", "{\n    \"debts\": [],")

        val error = runCatching { decodeValidated(newer) }.exceptionOrNull()

        assertTrue("was: $error", error is WalletException.BackupVersionUnsupported)
        assertEquals(2, (error as WalletException.BackupVersionUnsupported).version)
    }

    // Every row must be one the app can read back. A row it cannot read gets through the JSON decode
    // (kinds, themes and amounts are plain strings there) and then fails on every launch or every
    // read of its table. Refused here, the restore stops before anything is replaced.

    private fun withTransaction(id: Long, change: (TransactionDto) -> TransactionDto) =
        build().let { b -> b.copy(transactions = b.transactions.map { if (it.id == id) change(it) else it }) }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsATransactionOfAKindThisBuildCannotRead() {
        // What a backup from a newer build that adds a kind looks like to this one.
        decodeValidated(BackupCodec.encode(withTransaction(1) { it.copy(type = "LOAN") }))
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsATransferWithoutItsRate() {
        decodeValidated(BackupCodec.encode(withTransaction(3) { it.copy(rateUsed = null) }))
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsAnAmountThatIsNotANumber() {
        decodeValidated(BackupCodec.encode(withTransaction(2) { it.copy(primaryAmount = "12,99") }))
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsAThemeThisBuildDoesNotKnow() {
        // The theme is read before any screen draws; an unknown one would stop the app at launch.
        val backup = build().let { b -> b.copy(settings = b.settings?.copy(theme = "dark")) }
        decodeValidated(BackupCodec.encode(backup))
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsACategoryOfAnUnknownKind() {
        val backup = build().let { b -> b.copy(categories = b.categories.map { it.copy(kind = "DEBT") }) }
        decodeValidated(BackupCodec.encode(backup))
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsAnOpeningBalanceThatIsNotANumber() {
        val backup = build().let { b -> b.copy(accounts = b.accounts.map { if (it.id == 1L) it.copy(openingBalanceAmount = "lots") else it }) }
        decodeValidated(BackupCodec.encode(backup))
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsARateThatIsNotANumber() {
        val backup = build().let { b -> b.copy(rates = b.rates.map { it.copy(rateToBase = "") }) }
        decodeValidated(BackupCodec.encode(backup))
    }
}
