package org.aristonis.mywallet.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.aristonis.mywallet.data.fixture.LegacyFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * A backup written by 1.0 or 1.1 must turn into exactly what the database upgrade makes of the same
 * rows, so the two are checked against one shared list of old rows: the same headers and legs with
 * every amount's text intact, the same raw copies for rows the old app ignored or could not read,
 * the rates from the backup's own rates table, and the pre-made categories added once.
 */
class BackupUpgradeTest {

    private val v1 = WalletBackup(
        version = 1,
        settings = SettingsDto(baseCurrencyCode = LegacyFixture.baseCurrencyCode, theme = "SYSTEM", schemaVersion = 2),
        accounts = LegacyFixture.accounts.map {
            AccountDto(it.id, it.name, it.typeKey, it.currencyCode, it.openingBalanceAmount, it.archived, it.sortOrder)
        },
        transactions = LegacyFixture.cases.map { c ->
            val r = c.row
            TransactionDto(
                id = r.id, type = r.type, date = LocalDate.ofEpochDay(r.date), note = r.note,
                primaryAccountId = r.primaryAccountId, primaryAmount = r.primaryAmount, primaryCurrency = r.primaryCurrency,
                categoryId = r.categoryId, subCategoryId = r.subCategoryId,
                secondaryAccountId = r.secondaryAccountId, secondaryAmount = r.secondaryAmount,
                secondaryCurrency = r.secondaryCurrency, rateUsed = r.rateUsed,
            )
        },
        // A real export writes no parent as null, not 0.
        categories = LegacyFixture.categories.map { CategoryDto(it.id, it.name, it.kind, it.parentId.takeIf { p -> p != 0L }, it.systemKey) },
        currencies = emptyList(),
        rates = LegacyFixture.rates.map { (code, rate) -> RateDto(code, rate) },
    )

    private val v2 = BackupUpgrade.v1ToV2(v1)

    @Test
    fun theUpgradedFileIsFormatTwo() = assertEquals(2, v2.version)

    @Test
    fun everyOldRowBecomesTheHeaderTheDatabaseUpgradeMakes() {
        LegacyFixture.cases.forEach { c ->
            val entry = v2.entries.single { it.id == c.row.id }
            assertEquals("row ${c.row.id}", c.expectedKind, entry.kind)
            assertEquals("row ${c.row.id}", c.expectedPairRate, entry.pairRate)
            assertEquals("row ${c.row.id}", c.row.note, entry.note)
            assertEquals("row ${c.row.id}", LocalDate.ofEpochDay(c.row.date), entry.date)
        }
        assertEquals(LegacyFixture.cases.size, v2.entries.size)
    }

    @Test
    fun everyOldRowBecomesTheLegsTheDatabaseUpgradeMakesWithItsTextIntact() {
        LegacyFixture.cases.forEach { c ->
            val legs = v2.legs.filter { it.entryId == c.row.id }.sortedWith(compareBy({ it.role }, { it.position }))
            val expected = c.expectedLegs.sortedWith(compareBy({ it.role }, { it.position }))
            assertEquals(
                "row ${c.row.id}",
                expected.map { listOf(it.role, it.position, it.accountId, it.categoryId, it.subCategoryId, it.amount, it.currencyCode, it.rateToBase) },
                legs.map { listOf(it.role, it.position, it.accountId, it.categoryId, it.subCategoryId, it.amount, it.currencyCode, it.rateToBase) },
            )
            legs.forEach { assertNull(it.debtId) }
        }
    }

    @Test
    fun rowsTheOldAppIgnoredOrCouldNotReadKeepARawCopyWithTheirReason() {
        val expected = LegacyFixture.cases.filter { it.reason != null }.associate { it.row.id to it.reason }
        assertEquals(expected, v2.legacyRows.associate { it.id to it.reason })
        v2.legacyRows.forEach { copy ->
            val r = LegacyFixture.cases.single { it.row.id == copy.id }.row
            assertEquals(
                listOf(r.type, r.note, r.primaryAccountId, r.primaryAmount, r.primaryCurrency, r.categoryId, r.subCategoryId, r.secondaryAccountId, r.secondaryAmount, r.secondaryCurrency, r.rateUsed),
                listOf(copy.type, copy.note, copy.primaryAccountId, copy.primaryAmount, copy.primaryCurrency, copy.categoryId, copy.subCategoryId, copy.secondaryAccountId, copy.secondaryAmount, copy.secondaryCurrency, copy.rateUsed),
            )
        }
    }

    @Test
    fun rowsOnTheOtherKindOfCategoryOrOnAMissingCategoryConvertToTheLegsTheUpgradeMakesWithTheCategoryIdKept() {
        LegacyFixture.categoryMismatchRowIds.forEach { id ->
            val case = LegacyFixture.cases.single { it.row.id == id }
            val entry = v2.entries.single { it.id == id }
            val main = v2.legs.single { it.entryId == id && it.role == "MAIN" }

            assertEquals("row $id", case.expectedKind, entry.kind)
            assertEquals("row $id", case.row.categoryId, main.categoryId)
            assertEquals("row $id", case.row.subCategoryId, main.subCategoryId)
            assertEquals("row $id", case.row.primaryAmount, main.amount)
            assertEquals("row $id keeps no raw copy", 0, v2.legacyRows.count { it.id == id })
        }
    }

    @Test
    fun theConvertedFixtureFileIsOneTheRestoreChecksAccept() {
        val text = BackupCodec.encode(v2)

        assertEquals(v2, decodeValidated(text))
    }

    @Test
    fun ratesComeFromTheBackupsOwnRatesTable() {
        val changed = v1.copy(rates = v1.rates.map { if (it.currencyCode == "EUR") it.copy(rateToBase = "9.99") else it })
        val eurMain = BackupUpgrade.v1ToV2(changed).legs.filter { it.role == "MAIN" && it.currencyCode == "EUR" }
        check(eurMain.isNotEmpty())
        eurMain.forEach { assertEquals("9.99", it.rateToBase) }
    }

    @Test
    fun preMadeCategoriesAreAddedOnceAndAnExistingTaxAndFeesAreReused() {
        assertEquals(LegacyFixture.expectedCategoryCountAfter, v2.categories.size)
        assertEquals(1, v2.categories.count { it.name.equals("Tax", ignoreCase = true) && it.kind == "EXPENSE" && (it.parentId ?: 0L) == 0L })
        val added = v2.categories.filter { c -> LegacyFixture.categories.none { it.id == c.id } }
        assertEquals(
            listOf(
                CategoryDto(10, "Interest", "INCOME", null, null),
                CategoryDto(11, "Interest", "EXPENSE", null, null),
                CategoryDto(14, "Lost debt", "EXPENSE", null, null),
                CategoryDto(15, "Debt forgiven", "INCOME", null, null),
            ),
            added,
        )
    }

    /** Roles are compared by the category they point at, so the check holds whatever ids a database hands out. */
    @Test
    fun everyRoleNamesThePreMadeCategoryOfItsKind() {
        val byId = v2.categories.associateBy { it.id }
        val expected = LegacyFixture.expectedRoles.mapValues { (_, id) ->
            val c = (LegacyFixture.categories.map { it.id to (it.name.lowercase() to it.kind) } +
                listOf(10L to ("interest" to "INCOME"), 11L to ("interest" to "EXPENSE"), 14L to ("lost debt" to "EXPENSE"), 15L to ("debt forgiven" to "INCOME"))).toMap()
            c.getValue(id)
        }
        assertEquals(expected, v2.categoryRoles.associate { r -> r.role to byId.getValue(r.categoryId).let { it.name.lowercase() to it.kind } })
    }

    @Test
    fun legsAreListedInTheOrderTheDatabaseUpgradeInsertsThem() {
        val readable = v2.entries.filter { it.kind != LegacyFixture.KIND_UNREADABLE }.map { it.id }.sorted()
        val accountLegs = readable.map { it to (if (v2.entries.single { e -> e.id == it }.kind == "TRANSFER") "SOURCE" else "ACCOUNT") }
        val mainLegs = v2.entries.filter { it.kind == "INCOME" || it.kind == "EXPENSE" }.map { it.id }.sorted().map { it to "MAIN" }
        val destLegs = v2.entries.filter { it.kind == "TRANSFER" }.map { it.id }.sorted().map { it to "DEST" }
        assertEquals(accountLegs + mainLegs + destLegs, v2.legs.map { it.entryId to it.role })
    }

    @Test
    fun aFileWithoutSettingsStillConvertsWithNoRateTakenAsBase() {
        val converted = BackupUpgrade.v1ToV2(v1.copy(settings = null))
        val usdMain = converted.legs.first { it.role == "MAIN" && it.currencyCode == "USD" }
        assertEquals("1.0", usdMain.rateToBase)
    }

    @Test
    fun aRepeatedRowIdIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupUpgrade.v1ToV2(v1.copy(transactions = v1.transactions + v1.transactions.first()))
        }
    }

    @Test
    fun theFirstRateForACurrencyWinsWhenAFileRepeatsIt() {
        val repeated = v1.copy(rates = v1.rates + RateDto("EUR", "7.77"))
        BackupUpgrade.v1ToV2(repeated).legs.filter { it.role == "MAIN" && it.currencyCode == "EUR" }
            .forEach { assertEquals("1.08", it.rateToBase) }
    }

    @Test
    fun readerRefusesUnsupportedVersionsUnknownKeysMissingKeysAndAMissingVersion() {
        val json = BackupCodec.encode(v2)
        val original = Json.parseToJsonElement(json).jsonObject
        listOf(
            JsonObject(original + ("version" to JsonPrimitive(1))),
            JsonObject(original + ("version" to JsonPrimitive(4))),
            JsonObject(original + ("surprise" to JsonPrimitive(true))),
            JsonObject(original - "legacyRows"),
            JsonObject(original - "version"),
        ).forEach { mutation ->
            val bad = mutation.toString()
            assertNotEquals("the rejection fixture must change the file", json, bad)
            assertThrows(Exception::class.java) { BackupCodec.decodeV2(bad) }
        }
    }

    @Test
    fun everythingElseIsCarriedOverAsItWas() {
        assertEquals(v1.settings, v2.settings)
        assertEquals(v1.accounts.map { it.id to it.openingBalanceAmount }, v2.accounts.map { it.id to it.openingBalanceAmount })
        v2.accounts.forEach { assertNull(it.creditLimit); assertNull(it.defaultFeeMode); assertNull(it.defaultFeeValue) }
        assertEquals(v1.rates, v2.rates)
        assertEquals(emptyList<Any>(), v2.debts + v2.deductionItems + v2.deductionAutoCategories + v2.deductionLines)
    }

    @Test
    fun aFormatTwoFileSurvivesTheJsonRoundTripWithAmountsAsText() {
        val json = BackupCodec.encode(v2)
        assertEquals(v2, BackupCodec.decodeV2(json))
        assertTrue(json.contains("\"2500.00\""))
        assertTrue(json.contains("\"-12.345\""))
    }
}
