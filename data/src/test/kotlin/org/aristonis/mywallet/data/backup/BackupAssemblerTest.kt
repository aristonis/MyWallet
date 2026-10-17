package org.aristonis.mywallet.data.backup

import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CategoryRoleEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.EntryEntity
import org.aristonis.mywallet.data.db.EntryLegEntity
import org.aristonis.mywallet.data.db.LegacyUnreadableRowEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
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
        CategoryEntity(id = 10, name = "Salary", kind = "INCOME"),
        CategoryEntity(id = 11, name = "Food", kind = "EXPENSE"),
        // a sub-category (parentId set) exercises the nullable parent reference
        CategoryEntity(id = 21, name = "Groceries", kind = "EXPENSE", parentId = 11),
        CategoryEntity(id = 32, name = "Fees", kind = "EXPENSE"),
    )
    private val accounts = listOf(
        AccountEntity(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalanceAmount = "100.50"),
        AccountEntity(id = 2, name = "Bank", typeKey = "card", currencyCode = "EUR", openingBalanceAmount = "0.00"),
    )
    private val rates = listOf(RateEntity(currencyCode = "EUR", rateToBase = "1.086956"))
    private val entries = listOf(
        EntryEntity(id = 1, kind = "INCOME", date = LocalDate.of(2026, 1, 5)),
        EntryEntity(id = 2, kind = "EXPENSE", date = LocalDate.of(2026, 1, 6), note = "lunch"),
        // a transfer carries its applied rate on the header
        EntryEntity(id = 3, kind = "TRANSFER", date = LocalDate.of(2026, 1, 7), pairRate = "0.920000"),
    )
    private val legs = listOf(
        EntryLegEntity(entryId = 1, role = "ACCOUNT", position = 0, accountId = 1, amount = "1234.00", currencyCode = "USD"),
        EntryLegEntity(
            entryId = 1, role = "MAIN", position = 0, categoryId = 10, amount = "1234.00", currencyCode = "USD",
            rateToBase = "1",
        ),
        EntryLegEntity(entryId = 2, role = "ACCOUNT", position = 0, accountId = 1, amount = "-12.99", currencyCode = "USD"),
        EntryLegEntity(
            entryId = 2, role = "MAIN", position = 0, categoryId = 11, subCategoryId = 21, amount = "12.99",
            currencyCode = "USD", rateToBase = "1",
        ),
        EntryLegEntity(entryId = 3, role = "SOURCE", position = 0, accountId = 1, amount = "-20.000", currencyCode = "USD"),
        EntryLegEntity(entryId = 3, role = "DEST", position = 0, accountId = 2, amount = "18.40", currencyCode = "EUR"),
    )
    private val roles = listOf(CategoryRoleEntity(role = "FEES", categoryId = 32))
    private val rawCopies = listOf(
        LegacyUnreadableRowEntity(
            id = 9, type = "REFUND", date = LocalDate.of(2026, 1, 2), primaryAccountId = 1,
            primaryAmount = "5.00", primaryCurrency = "USD", reason = "UNKNOWN_TYPE",
        ),
    )
    private val settings = SettingsEntity(baseCurrencyCode = "USD", theme = "SYSTEM", schemaVersion = 4)

    private fun snapshot(settingsRow: SettingsEntity? = settings, categoryRows: List<CategoryEntity> = categories) =
        TableSnapshot(
            settings = settingsRow,
            accounts = accounts,
            categories = categoryRows,
            currencies = currencies,
            rates = rates,
            entries = entries + EntryEntity(id = 9, kind = "LEGACY_UNREADABLE", date = LocalDate.of(2026, 1, 2)),
            legs = legs,
            debts = emptyList(),
            deductionItems = emptyList(),
            deductionAutoCategories = emptyList(),
            deductionLines = emptyList(),
            categoryRoles = roles,
            legacyRows = rawCopies,
        )

    private fun build(settingsRow: SettingsEntity? = settings) = buildBackup(snapshot(settingsRow))

    @Test
    fun buildBackup_roundTripsThroughEncodeAndValidatedDecode() {
        val built = build()
        assertEquals(3, built.version)
        assertEquals(built, decodeValidated(BackupCodec.encode(built)))
    }

    @Test
    fun buildBackup_carriesEveryTableItWasGiven() {
        val built = build()

        assertEquals(4, built.entries.size)
        assertEquals(legs.size, built.legs.size)
        assertEquals("-20.000", built.legs.single { it.entryId == 3L && it.role == "SOURCE" }.amount)
        assertEquals(listOf(CategoryRoleDto("FEES", 32)), built.categoryRoles)
        assertEquals(listOf(9L to "UNKNOWN_TYPE"), built.legacyRows.map { it.id to it.reason })
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
        val newerFormat = BackupCodec.encode(build().copy(version = 4))
        decodeValidated(newerFormat)
    }

    @Test
    fun buildBackup_carriesTheSystemKeyOfAnAppOwnedBucket() {
        // Identity of the fallback bucket is the key, not the display name, so a backup that dropped
        // it would restore the bucket as an ordinary category the user could then delete.
        val withBucket = buildBackup(
            snapshot(
                categoryRows = categories + CategoryEntity(
                    id = 99, name = "Uncategorized", kind = "EXPENSE", systemKey = "UNCATEGORIZED_EXPENSE",
                ),
            ),
        )
        val restored = decodeValidated(BackupCodec.encode(withBucket))

        assertEquals("UNCATEGORIZED_EXPENSE", restored.categories.first { it.id == 99L }.systemKey)
        assertNull("a user category must not gain a key", restored.categories.first { it.id == 11L }.systemKey)
    }

    @Test
    fun decodeValidated_acceptsABackupWhoseCategoriesCarryNoSystemKeyField() {
        // The field is optional in the shared category DTO; a file without it must still restore,
        // with every category read back as user-owned rather than the decode failing on a missing key.
        // The document is pretty-printed, so the field is stripped by pattern rather than by literal
        // text; it is written last in the object, so the comma taken with it is always the preceding one.
        val withoutTheField = BackupCodec.encode(build())
            .replace(Regex(",\\s*\"systemKey\"\\s*:\\s*null"), "")
        val restored = decodeValidated(withoutTheField)

        assertTrue("the field should be gone from the document", !withoutTheField.contains("systemKey"))
        assertTrue("every category reads back as user-owned", restored.categories.all { it.systemKey == null })
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsALegNamingASubCategoryTheBackupDoesNotCarry() {
        // Legs have a foreign key on their sub-category, so this would otherwise abort the restore
        // part-way with a raw SQLite error the user can do nothing with.
        val dangling = withLegs { if (it.entryId == 2L && it.role == "MAIN") it.copy(subCategoryId = 4242) else it }
        decodeValidated(BackupCodec.encode(dangling))
    }

    @Test
    fun decodeValidated_acceptsALegWhoseMainCategoryIsMissing() {
        // The old table had no key on the main category, so an upgraded wallet can hold such a leg,
        // and a backup of that wallet has to restore. Only the sub-category has a key.
        val dangling = withLegs { if (it.entryId == 1L && it.role == "MAIN") it.copy(categoryId = 4242) else it }

        assertEquals(dangling, decodeValidated(BackupCodec.encode(dangling)))
    }

    @Test
    fun decodeValidated_acceptsATransferWhichNamesNoCategoryAtAll() {
        // Transfers are never categorized, so legs with no category must not be mistaken for dangling ones.
        val transfersOnly = build().let { b ->
            b.copy(entries = b.entries.filter { it.kind == "TRANSFER" }, legs = b.legs.filter { it.entryId == 3L }, legacyRows = emptyList())
        }

        assertEquals(1, decodeValidated(BackupCodec.encode(transfersOnly)).entries.size)
    }

    @Test
    fun aBackupFromANewerBuildSaysSoEvenWithFieldsThisBuildDoesNotKnow() {
        val newer = BackupCodec.encode(build().copy(version = 4))
            .replaceFirst("{", "{\n    \"loans\": [],")

        val error = runCatching { decodeValidated(newer) }.exceptionOrNull()

        assertTrue("was: $error", error is WalletException.BackupVersionUnsupported)
        assertEquals(4, (error as WalletException.BackupVersionUnsupported).version)
    }

    // Every row must be one the app can read back. A row it cannot read gets through the JSON decode
    // (kinds, themes and amounts are plain strings there) and then fails on every launch or every
    // read of its table. Refused here, the restore stops before anything is replaced.

    private fun withLegs(change: (LegDto) -> LegDto) = build().let { b -> b.copy(legs = b.legs.map(change)) }

    private fun withEntry(id: Long, change: (EntryDto) -> EntryDto) =
        build().let { b -> b.copy(entries = b.entries.map { if (it.id == id) change(it) else it }) }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsAnEntryOfAKindThisBuildCannotRead() {
        // What a backup from a newer build that adds a kind looks like to this one.
        decodeValidated(BackupCodec.encode(withEntry(1) { it.copy(kind = "LOAN") }))
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsATransferWithoutItsRate() {
        decodeValidated(BackupCodec.encode(withEntry(3) { it.copy(pairRate = null) }))
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsAnAmountThatIsNotANumber() {
        decodeValidated(BackupCodec.encode(withLegs { if (it.entryId == 2L) it.copy(amount = "12,99") else it }))
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

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsALegOfAnEntryTheFileDoesNotCarry() {
        val orphan = LegDto(
            entryId = 77, role = "ACCOUNT", position = 0, accountId = 1, debtId = null, categoryId = null,
            subCategoryId = null, amount = "1", currencyCode = "USD", rateToBase = null,
        )
        decodeValidated(BackupCodec.encode(build().let { it.copy(legs = it.legs + orphan) }))
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsTwoEntriesWithOneId() {
        decodeValidated(BackupCodec.encode(build().let { it.copy(entries = it.entries + it.entries.first()) }))
    }

    @Test(expected = WalletException.BackupInvalid::class)
    fun decodeValidated_rejectsADebtLegOnADebtTheFileDoesNotCarry() {
        decodeValidated(
            BackupCodec.encode(withLegs { if (it.entryId == 2L && it.role == "ACCOUNT") it.copy(accountId = null, debtId = 5) else it }),
        )
    }
}
