package org.aristonis.mywallet.data.fixture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture is the one list of old-format rows that both the database upgrade and the old-backup
 * converter are checked against, so its own shape is pinned here: if it stops covering a case, both
 * of those checks quietly stop covering it too.
 */
class LegacyFixtureTest {

    private val readable = LegacyFixture.cases.filter { it.reason == null }

    /** Every row that gets legs: the readable ones plus those whose ignored columns are only copied aside. */
    private val migrated = LegacyFixture.cases.filter { it.expectedKind != LegacyFixture.KIND_UNREADABLE }

    @Test
    fun rowIdsAreUnique() {
        val ids = LegacyFixture.cases.map { it.row.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyUnreadableReasonAppearsAtLeastOnce() {
        val reasons = LegacyFixture.cases.mapNotNull { it.reason }.toSet()
        assertEquals(LegacyFixture.REASONS.toSet(), reasons)
    }

    @Test
    fun ignoredColumnsCoverAStrayRateOnAnExpenseAndAStrayCategoryOnATransfer() {
        val ignored = LegacyFixture.cases.filter { it.reason == LegacyFixture.IGNORED_COLUMNS }
        assertTrue(ignored.any { it.row.type == LegacyFixture.EXPENSE && it.row.rateUsed != null })
        val transfer = ignored.single { it.row.type == LegacyFixture.TRANSFER }
        assertTrue(transfer.row.categoryId != null)
        assertEquals(LegacyFixture.TRANSFER, transfer.expectedKind)
        assertTrue(transfer.expectedLegs.all { it.categoryId == null && it.subCategoryId == null })
    }

    @Test
    fun aMissingDestinationWinsOverAStrayCategory() {
        assertTrue(
            LegacyFixture.cases.any {
                it.reason == LegacyFixture.MISSING_DESTINATION && it.row.categoryId != null
            },
        )
    }

    @Test
    fun anIncomeWithoutACategoryIsUnreadable() {
        assertTrue(
            LegacyFixture.cases.any {
                it.reason == LegacyFixture.MISSING_CATEGORY && it.row.type == LegacyFixture.INCOME
            },
        )
    }

    @Test
    fun transfersKeepTheirRateAsThePairRateAndNothingElseHasOne() {
        LegacyFixture.cases.forEach { case ->
            val keepsRate = case.row.type == LegacyFixture.TRANSFER && case.reason != LegacyFixture.MISSING_DESTINATION
            assertEquals(case.row.id.toString(), if (keepsRate) case.row.rateUsed else null, case.expectedPairRate)
        }
    }

    @Test
    fun theUpgradeReusesTheExistingTaxAndFeesAndAddsTheRest() {
        val byName = LegacyFixture.categories.associateBy { it.name.lowercase() to it.kind }
        assertEquals(byName.getValue("fees" to LegacyFixture.EXPENSE).id, LegacyFixture.expectedRoles.getValue("FEES"))
        assertEquals(
            setOf("INTEREST_IN", "INTEREST_OUT", "FEES", "LOST_DEBT", "DEBT_FORGIVEN"),
            LegacyFixture.expectedRoles.keys,
        )
        val added = LegacyFixture.expectedRoles.values.filter { id -> LegacyFixture.categories.none { it.id == id } }
        val taxAdded = if (LegacyFixture.categories.any { it.name.equals("Tax", ignoreCase = true) && it.kind == LegacyFixture.EXPENSE && it.parentId == 0L }) 0 else 1
        assertEquals(LegacyFixture.categories.size + added.size + taxAdded, LegacyFixture.expectedCategoryCountAfter)
    }

    @Test
    fun textThatOnlySurvivesAByteForByteCopyIsPresent() {
        assertTrue(migrated.any { it.row.primaryAmount.startsWith("0.") || it.row.primaryAmount.startsWith(".") })
        assertTrue(migrated.any { note -> note.row.note?.let { '"' in it && '\n' in it && it.any { c -> c.code > 127 } } == true })
    }

    @Test
    fun aSavedRateForTheBaseCurrencyNeverBeatsRateOne() {
        assertTrue(LegacyFixture.baseCurrencyCode in LegacyFixture.rates)
    }

    @Test
    fun theSequenceLeavesAGapAboveTheLastRow() {
        assertTrue(LegacyFixture.sequenceBefore > LegacyFixture.cases.maxOf { it.row.id })
    }

    @Test
    fun everyReadableRowExpectsTwoLegsAndKeepsItsType() {
        readable.forEach { case ->
            assertEquals(case.row.id.toString(), 2, case.expectedLegs.size)
            assertEquals(case.row.type, case.expectedKind)
        }
    }

    @Test
    fun unreadableRowsExpectNoLegsExceptIgnoredColumns() {
        LegacyFixture.cases.filter { it.reason != null }.forEach { case ->
            if (case.reason == LegacyFixture.IGNORED_COLUMNS) {
                assertEquals(case.row.type, case.expectedKind)
                assertEquals(2, case.expectedLegs.size)
            } else {
                assertEquals(LegacyFixture.KIND_UNREADABLE, case.expectedKind)
                assertTrue(case.expectedLegs.isEmpty())
            }
        }
    }

    @Test
    fun coversEveryTypeAndBothKindsOfTransfer() {
        val types = readable.map { it.row.type }.toSet()
        assertEquals(setOf(LegacyFixture.INCOME, LegacyFixture.EXPENSE, LegacyFixture.TRANSFER), types)
        val transfers = readable.filter { it.row.type == LegacyFixture.TRANSFER }
        assertTrue(transfers.any { it.row.primaryCurrency != it.row.secondaryCurrency })
        assertTrue(transfers.any { it.row.primaryCurrency == it.row.secondaryCurrency })
    }

    @Test
    fun aRowInACurrencyWithNoRateExpectsNoStoredRate() {
        val noRate = readable.filter {
            it.row.type != LegacyFixture.TRANSFER &&
                it.row.primaryCurrency != LegacyFixture.baseCurrencyCode &&
                it.row.primaryCurrency !in LegacyFixture.rates
        }
        assertTrue(noRate.isNotEmpty())
        noRate.forEach { case -> assertNull(case.expectedLegs.single { it.role == "MAIN" }.rateToBase) }
    }

    @Test
    fun baseCurrencyRowsStoreRateOneAndRatedRowsStoreTheirSavedRate() {
        migrated.filter { it.row.type != LegacyFixture.TRANSFER }.forEach { case ->
            val main = case.expectedLegs.single { it.role == "MAIN" }
            when (case.row.primaryCurrency) {
                LegacyFixture.baseCurrencyCode -> assertEquals("1", main.rateToBase)
                in LegacyFixture.rates -> assertEquals(LegacyFixture.rates[case.row.primaryCurrency], main.rateToBase)
                else -> assertNull(main.rateToBase)
            }
        }
    }

    @Test
    fun coversTheCategoryAndAccountEdgeCases() {
        assertTrue(LegacyFixture.categories.any { it.name == "Tax" && it.kind == LegacyFixture.EXPENSE && it.parentId == 0L })
        assertTrue(LegacyFixture.categories.any { it.name == "fees" && it.kind == LegacyFixture.EXPENSE && it.parentId == 0L })
        assertTrue(LegacyFixture.accounts.any { it.archived })
        assertTrue(readable.any { it.row.note == "" })
        assertTrue(readable.any { it.row.note == null })
        val parentsWithChildren = LegacyFixture.categories.filter { it.parentId != 0L }.map { it.parentId }.toSet()
        assertTrue(readable.any { it.row.categoryId in parentsWithChildren && it.row.subCategoryId == null })
    }

    @Test
    fun expectedAmountsAreTheStoredTextSignedByDirection() {
        migrated.forEach { case ->
            val account = case.expectedLegs.single { it.role == "ACCOUNT" || it.role == "SOURCE" }
            val expected = if (case.row.type == LegacyFixture.INCOME) case.row.primaryAmount else "-" + case.row.primaryAmount
            assertEquals(expected, account.amount)
        }
    }
}
