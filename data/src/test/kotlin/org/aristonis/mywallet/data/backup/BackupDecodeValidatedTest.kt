package org.aristonis.mywallet.data.backup

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a restore accepts, before it touches a single row. A file is read by its own format version:
 * a format-1 file goes through the converter to format 2, a format-2 file decodes directly, and
 * anything else is refused. Only then do the format-2 checks run, so an old file is held to the same
 * rules as a new one and a bad file never reaches the database.
 *
 * Assumed signature, which the implementation must provide: `decodeValidated(text: String):
 * WalletBackupV2`, replacing the format-1 version of that function.
 */
class BackupDecodeValidatedTest {

    private val sample = BackupV2Samples.wallet()

    private fun refusedAs(text: String): Throwable = assertThrows(WalletException::class.java) { decodeValidated(text) }

    private fun assertInvalid(backup: WalletBackupV2) {
        assertTrue(refusedAs(BackupCodec.encode(backup)) is WalletException.BackupInvalid)
    }

    // --- Dispatch on the version in the file ---

    @Test
    fun theCurrentFormatIsVersionTwo() = assertEquals(2, CURRENT_BACKUP_VERSION)

    @Test
    fun aFormatTwoFileDecodesDirectlyToWhatWasWritten() {
        assertEquals(sample, decodeValidated(BackupCodec.encode(sample)))
    }

    @Test
    fun aFormatOneFileGoesThroughTheConverterAndComesOutAsFormatTwo() {
        val decoded = decodeValidated(BackupV2Samples.versionOneText())

        assertEquals(2, decoded.version)
        assertEquals(listOf("INCOME", "EXPENSE", "TRANSFER"), decoded.entries.sortedBy { it.id }.map { it.kind })
        assertEquals("0.920000", decoded.entries.single { it.id == 3L }.pairRate)
        assertEquals(
            "the pre-made categories come from the converter",
            setOf("Interest", "Tax", "Fees", "Lost debt", "Debt forgiven"),
            decoded.categories.map { it.name }.toSet() - setOf("Salary", "Food", "Groceries"),
        )
        assertEquals(5, decoded.categoryRoles.size)
        assertTrue(decoded.legacyRows.isEmpty())
    }

    @Test
    fun aFileFromANewerFormatIsRefusedWithItsVersionEvenWhenItHoldsKeysThisBuildDoesNotKnow() {
        val text = BackupCodec.encode(sample.copy(version = 3)).replaceFirst("\"entries\"", "\"fromTheFuture\": [], \"entries\"")

        val thrown = refusedAs(text)

        assertTrue(thrown is WalletException.BackupVersionUnsupported)
        assertEquals(3, (thrown as WalletException.BackupVersionUnsupported).version)
    }

    @Test
    fun aVersionBelowOneIsRefused() {
        val thrown = refusedAs(BackupCodec.encode(sample.copy(version = 0)))

        assertTrue(thrown is WalletException.BackupVersionUnsupported)
    }

    @Test
    fun textThatIsNotABackupIsInvalid() {
        assertTrue(refusedAs("{ not valid json") is WalletException.BackupInvalid)
    }

    @Test
    fun aFormatTwoFileWithoutSettingsIsInvalid() {
        assertInvalid(sample.copy(settings = null))
    }

    // --- Format-1 files: converted first, then only the format-2 checks ---

    @Test
    fun aFormatOneFileHoldingAnUnknownTypeRowDecodesWithAnUnreadableEntryAndItsRawCopy() {
        val oddRow = BackupV2Samples.versionOneTransaction(
            14, "REFUND", "2026-03-01", 1, "10.00", "USD", categoryId = BackupV2Samples.FOOD,
        )

        val decoded = decodeValidated(BackupV2Samples.versionOneText(BackupV2Samples.versionOneTransactions() + oddRow))

        assertEquals("LEGACY_UNREADABLE", decoded.entries.single { it.id == 14L }.kind)
        assertTrue(decoded.legs.none { it.entryId == 14L })
        assertEquals(listOf(14L to "UNKNOWN_TYPE"), decoded.legacyRows.map { it.id to it.reason })
    }

    @Test
    fun aFormatOneFileWithAnExpenseThatHasNoCategoryDecodesAsMissingCategory() {
        val noCategory = BackupV2Samples.versionOneTransaction(15, "EXPENSE", "2026-03-02", 1, "8.20", "USD")

        val decoded = decodeValidated(BackupV2Samples.versionOneText(BackupV2Samples.versionOneTransactions() + noCategory))

        assertEquals(listOf(15L to "MISSING_CATEGORY"), decoded.legacyRows.map { it.id to it.reason })
    }

    @Test
    fun aFormatOneFileWithTwoRowsOfOneIdBecomesBackupInvalidNotACrash() {
        val twice = BackupV2Samples.versionOneTransactions() + BackupV2Samples.versionOneTransactions().first()

        assertTrue(refusedAs(BackupV2Samples.versionOneText(twice)) is WalletException.BackupInvalid)
    }

    // --- Unreadable entries and their raw copies ---

    @Test
    fun anUnreadableEntryWithItsRawCopyIsAcceptedForEachReasonThatLeavesNoLegs() {
        listOf("UNKNOWN_TYPE", "MISSING_CATEGORY", "MISSING_DESTINATION").forEach { reason ->
            val file = BackupV2Samples.walletWithUnreadableRow(id = 4, reason = reason)

            assertEquals(reason, file, decodeValidated(BackupCodec.encode(file)))
        }
    }

    @Test
    fun anUnreadableEntryWithNoRawCopyIsInvalid() {
        assertInvalid(sample.copy(entries = sample.entries + BackupV2Samples.unreadableEntry(4)))
    }

    @Test
    fun anUnreadableEntryWhoseRawCopyIsForAnotherIdIsInvalid() {
        val file = BackupV2Samples.walletWithUnreadableRow(id = 4).copy(legacyRows = listOf(BackupV2Samples.rawCopy(5)))

        assertInvalid(file)
    }

    @Test
    fun anUnreadableEntryWhoseRawCopyReasonKeepsTheRowReadableIsInvalid() {
        assertInvalid(BackupV2Samples.walletWithUnreadableRow(id = 4, reason = "IGNORED_COLUMNS"))
    }

    @Test
    fun anUnreadableEntryWhoseRawCopyReasonIsUnknownIsInvalid() {
        assertInvalid(BackupV2Samples.walletWithUnreadableRow(id = 4, reason = "SOMETHING_ELSE"))
    }

    @Test
    fun anUnreadableEntryThatHasALegIsInvalid() {
        val file = BackupV2Samples.walletWithUnreadableRow(id = 4).let {
            it.copy(legs = it.legs + BackupV2Samples.account(4, "ACCOUNT", BackupV2Samples.CASH, "10.00", "USD"))
        }

        assertInvalid(file)
    }

    @Test
    fun twoRawCopiesOfOneIdAreInvalid() {
        val file = BackupV2Samples.walletWithUnreadableRow(id = 4).let {
            it.copy(legacyRows = it.legacyRows + BackupV2Samples.rawCopy(4, "MISSING_CATEGORY"))
        }

        assertInvalid(file)
    }

    @Test
    fun aRawCopyWithNoEntryIsAccepted() {
        val file = sample.copy(legacyRows = listOf(BackupV2Samples.rawCopy(40)))

        assertEquals(file, decodeValidated(BackupCodec.encode(file)))
    }

    @Test
    fun aRawCopyThatNoLongerMatchesItsReadableEntryIsAccepted() {
        val file = sample.copy(legacyRows = listOf(BackupV2Samples.rawCopy(1, reason = "IGNORED_COLUMNS", type = "EXPENSE")))

        assertEquals(file, decodeValidated(BackupCodec.encode(file)))
    }

    @Test
    fun anEntryWhoseKindNoRecipeReadsIsInvalid() {
        assertInvalid(sample.copy(entries = sample.entries.map { if (it.id == 2L) it.copy(kind = "FROM_A_NEWER_BUILD") else it }))
    }

    // --- Legs, categories and references ---

    @Test
    fun anEntryMissingTheLegItsKindNeedsIsInvalid() {
        assertInvalid(sample.copy(legs = sample.legs.filterNot { it.entryId == 3L && it.role == "DEST" }))
    }

    @Test
    fun anAmountThatIsNotANumberIsInvalid() {
        assertInvalid(sample.copy(legs = sample.legs.map { if (it.entryId == 1L) it.copy(amount = "12,50") else it }))
    }

    // An upgraded wallet can hold an entry on the other kind of category or on a deleted category,
    // so a backup of it has to restore. Saving such an entry anew is refused elsewhere.

    private fun assertAccepted(backup: WalletBackupV2) = assertEquals(backup, decodeValidated(BackupCodec.encode(backup)))

    @Test
    fun anIncomeLegOnAnExpenseCategoryIsAccepted() {
        assertAccepted(
            sample.copy(
                legs = sample.legs.map {
                    if (it.entryId == 1L && it.role == "MAIN") it.copy(categoryId = BackupV2Samples.FOOD) else it
                },
            ),
        )
    }

    @Test
    fun anExpenseLegOnAnIncomeCategoryIsAccepted() {
        assertAccepted(
            sample.copy(
                legs = sample.legs.map {
                    if (it.entryId == 2L && it.role == "MAIN") it.copy(categoryId = BackupV2Samples.SALARY, subCategoryId = null) else it
                },
            ),
        )
    }

    @Test
    fun aCategoryLegOnACategoryTheFileDoesNotContainIsAccepted() {
        assertAccepted(
            sample.copy(
                legs = sample.legs.map { if (it.entryId == 1L && it.role == "MAIN") it.copy(categoryId = 999) else it },
            ),
        )
    }

    @Test
    fun aCategoryOnALegThatIsNotCountedIsStillInvalid() {
        assertInvalid(
            sample.copy(
                legs = sample.legs.map {
                    if (it.entryId == 1L && it.role == "ACCOUNT") it.copy(categoryId = BackupV2Samples.SALARY) else it
                },
            ),
        )
    }

    @Test
    fun aFormatOneFileWithRowsOnTheOtherKindOfCategoryAndOnAMissingCategoryIsAcceptedAndKeepsTheCategoryIds() {
        val rows = BackupV2Samples.versionOneTransactions() + listOf(
            BackupV2Samples.versionOneTransaction(4, "EXPENSE", "2026-03-01", 1, "33.30", "USD", categoryId = BackupV2Samples.SALARY),
            BackupV2Samples.versionOneTransaction(5, "INCOME", "2026-03-02", 1, "14.10", "USD", categoryId = BackupV2Samples.FOOD),
            BackupV2Samples.versionOneTransaction(6, "INCOME", "2026-03-03", 1, "5.55", "USD", categoryId = 999),
        )

        val decoded = decodeValidated(BackupV2Samples.versionOneText(rows))

        assertEquals(
            listOf(4L to BackupV2Samples.SALARY, 5L to BackupV2Samples.FOOD, 6L to 999L),
            decoded.legs.filter { it.role == "MAIN" && it.entryId >= 4 }.sortedBy { it.entryId }.map { it.entryId to it.categoryId },
        )
        assertTrue("these rows are read cleanly, so no raw copy", decoded.legacyRows.isEmpty())
    }

    // --- Rows the database's unique indexes would refuse: BackupInvalid, never a raw SQLite error ---

    @Test
    fun twoLegsOfOneEntryWithTheSameRoleAndPositionAreInvalid() {
        assertInvalid(sample.copy(legs = sample.legs + sample.legs.first { it.entryId == 1L && it.role == "ACCOUNT" }))
    }

    @Test
    fun twoLegsOfOneEntryWithTheSameRoleAndPositionAreInvalidEvenWhenTheirAmountsDiffer() {
        val first = sample.legs.first { it.entryId == 2L && it.role == "ACCOUNT" }

        assertInvalid(sample.copy(legs = sample.legs + first.copy(amount = "-1.00")))
    }

    @Test
    fun twoTopLevelCategoriesOfOneKindWithTheSameNameInAnotherLetterCaseAreInvalid() {
        assertInvalid(sample.copy(categories = sample.categories + CategoryDto(id = 50, name = "FOOD", kind = "EXPENSE")))
    }

    @Test
    fun twoTopLevelCategoriesOfOneKindWithTheSameNameAreInvalid() {
        assertInvalid(sample.copy(categories = sample.categories + CategoryDto(id = 50, name = "Salary", kind = "INCOME")))
    }

    @Test
    fun twoSubCategoriesUnderOneParentWithTheSameNameInAnotherLetterCaseAreInvalid() {
        val twin = CategoryDto(id = 50, name = "groceries", kind = "EXPENSE", parentId = BackupV2Samples.FOOD)

        assertInvalid(sample.copy(categories = sample.categories + twin))
    }

    @Test
    fun twoCategoriesWithTheSameSystemKeyAreInvalid() {
        val keyed = listOf(
            CategoryDto(id = 50, name = "Uncategorized", kind = "EXPENSE", systemKey = "UNCATEGORIZED_EXPENSE"),
            CategoryDto(id = 51, name = "Other", kind = "EXPENSE", systemKey = "UNCATEGORIZED_EXPENSE"),
        )

        assertInvalid(sample.copy(categories = sample.categories + keyed))
    }

    @Test
    fun theSameNameOnAnotherKindOrUnderAnotherParentIsAccepted() {
        val file = sample.copy(
            categories = sample.categories + listOf(
                CategoryDto(id = 50, name = "Food", kind = "INCOME"),
                CategoryDto(id = 51, name = "Pantry", kind = "EXPENSE"),
                CategoryDto(id = 52, name = "Groceries", kind = "EXPENSE", parentId = 51),
            ),
        )

        assertAccepted(file)
    }

    @Test
    fun manyCategoriesWithNoSystemKeyAreAccepted() {
        assertTrue(sample.categories.count { it.systemKey == null } > 1)
        assertAccepted(sample)
    }

    @Test
    fun twoNamesThatDifferOnlyInALetterSqliteDoesNotFoldAreAccepted() {
        // The database's NOCASE folds only A-Z, so it holds both; refusing the file would be wrong.
        val file = sample.copy(
            categories = sample.categories + listOf(
                CategoryDto(id = 50, name = "\u00C9clair", kind = "EXPENSE"),
                CategoryDto(id = 51, name = "\u00E9clair", kind = "EXPENSE"),
            ),
        )

        assertAccepted(file)
    }

    @Test
    fun aSubCategoryTheFileDoesNotContainIsInvalid() {
        assertInvalid(
            sample.copy(
                legs = sample.legs.map { if (it.entryId == 2L && it.role == "MAIN") it.copy(subCategoryId = 999) else it },
            ),
        )
    }

    @Test
    fun aCategoryRoleThatPointsAtNoCategoryIsInvalid() {
        assertInvalid(sample.copy(categoryRoles = sample.categoryRoles + CategoryRoleDto("GHOST_ROLE", 999)))
    }
}
