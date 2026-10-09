package org.aristonis.mywallet.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.repo.RoomLedgerQueries
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * The read side of the ledger: balance and Tracking projections, the unknown-kind guard, the
 * account-in-use check and the rate fill. They run on the real database because the queries are the
 * contract, and the ones behind every screen must stay on covering indexes as the history grows.
 *
 * Assumed names, which the implementation must provide:
 * - `WalletDatabase.ledgerQueryDao()` and `RoomLedgerQueries(dao: LedgerQueryDao)` implementing
 *   the `LedgerQueries` port;
 * - three top-level `const val` SQL strings in package `org.aristonis.mywallet.data.db` that the DAO's
 *   `@Query` annotations use, so the plan test reads the exact text the app runs:
 *   `ACCOUNT_LEGS_SQL` (the account-leg projection, no parameters), `DEBT_LEGS_SQL` (the debt-leg
 *   projection, no parameters) and `ACCOUNT_IN_USE_SQL` (one parameter, the account id).
 */
@RunWith(AndroidJUnit4::class)
class LedgerQueryDaoTest {

    private lateinit var db: WalletDatabase
    private lateinit var queries: RoomLedgerQueries

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        queries = RoomLedgerQueries(db.ledgerQueryDao())
    }

    @After
    fun tearDown() = db.close()

    private val sql get() = db.openHelper.writableDatabase

    private fun entry(id: Long, kind: String = "EXPENSE", date: Long = 20454) {
        sql.execSQL("INSERT INTO entries (id, kind, date) VALUES (?, ?, ?)", arrayOf<Any?>(id, kind, date))
    }

    private fun accountLeg(entryId: Long, role: String, accountId: Long, amount: String, currency: String = "USD") {
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) VALUES (?, ?, 0, ?, ?, ?)",
            arrayOf<Any?>(entryId, role, accountId, amount, currency),
        )
    }

    private fun categoryLeg(entryId: Long, amount: String, currency: String, rate: String?, categoryId: Long = 3) {
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, categoryId, amount, currencyCode, rateToBase) " +
                "VALUES (?, 'MAIN', 0, ?, ?, ?, ?)",
            arrayOf<Any?>(entryId, categoryId, amount, currency, rate),
        )
    }

    private fun plan(statement: String, vararg args: Any?): String =
        sql.query("EXPLAIN QUERY PLAN $statement", arrayOf(*args)).use { cursor ->
            buildString {
                while (cursor.moveToNext()) append(cursor.getString(cursor.getColumnIndexOrThrow("detail"))).append('\n')
            }
        }

    /**
     * The month read must start from the date index, so its cost follows the month, not the whole
     * history. Without table statistics SQLite would otherwise pick a leg index for `IS NULL` and walk
     * almost every leg. Assumes the DAO query text is the constant `CATEGORY_LEGS_SQL`.
     */
    @Test
    fun theMonthReadStartsFromTheDateIndexAndWalksOnlyThatMonthsLegs() {
        val plan = plan(CATEGORY_LEGS_SQL, 20454, 20484)

        assertTrue("query plan was: $plan", plan.lineSequence().first().contains("index_entries_date"))
        assertFalse("query plan was: $plan", plan.contains("index_entry_legs_debtId_amount"))
        assertFalse("query plan was: $plan", plan.contains("index_entry_legs_accountId_amount"))
        assertFalse("query plan was: $plan", plan.contains("USE TEMP B-TREE"))
    }

    @Test
    fun accountLegsAreReadFromACoveringIndex() {
        val plan = plan(ACCOUNT_LEGS_SQL)

        assertTrue("query plan was: $plan", plan.contains("USING COVERING INDEX index_entry_legs_accountId_amount"))
    }

    @Test
    fun debtLegsAreReadFromACoveringIndex() {
        val plan = plan(DEBT_LEGS_SQL)

        assertTrue("query plan was: $plan", plan.contains("USING COVERING INDEX index_entry_legs_debtId_amount"))
    }

    @Test
    fun theAccountInUseCheckIsAnExistsQueryThatStopsAtTheFirstLeg() {
        assertTrue("it must be an EXISTS, not a COUNT", ACCOUNT_IN_USE_SQL.contains("EXISTS", ignoreCase = true))
        // a word match, since the column name accountId contains "count"
        assertFalse("it must not count every leg", Regex("\\bCOUNT\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(ACCOUNT_IN_USE_SQL))

        val plan = plan(ACCOUNT_IN_USE_SQL, 1)

        assertTrue("query plan was: $plan", plan.contains("INDEX index_entry_legs_accountId_amount"))
    }

    @Test
    fun observeAccountLegsReturnsEveryAccountSourceAndDestinationLegAndNothingElse() = runTest {
        // an account leg takes its currency from its account
        sql.execSQL(
            "INSERT INTO accounts (id, name, typeKey, currencyCode, openingBalanceAmount, archived, sortOrder) " +
                "VALUES (1, 'Cash', 'cash', 'USD', '0', 0, 0), (2, 'Euro', 'cash', 'EUR', '0', 0, 1)",
        )
        entry(1)
        accountLeg(1, "ACCOUNT", accountId = 1, amount = "-45.50")
        categoryLeg(1, amount = "45.50", currency = "USD", rate = "1")
        entry(2, kind = "TRANSFER")
        accountLeg(2, "SOURCE", accountId = 1, amount = "-100.00")
        accountLeg(2, "DEST", accountId = 2, amount = "92.59", currency = "EUR")

        val legs = queries.observeAccountLegs().first()

        assertEquals(
            setOf(
                AccountLeg(1, Money.of("-45.50", "USD")),
                AccountLeg(1, Money.of("-100.00", "USD")),
                AccountLeg(2, Money.of("92.59", "EUR")),
            ),
            legs.toSet(),
        )
        assertEquals("a category leg is not an account leg", 3, legs.size)
    }

    @Test
    fun anEntryOfAKindNoRecipeReadsIsReportedAsUnreadable() = runTest {
        entry(1, kind = "INCOME")
        entry(2, kind = "LEGACY_UNREADABLE")

        val reported = queries.observeUnknownKind(known = setOf("INCOME", "EXPENSE", "TRANSFER")).first()

        assertEquals(UnreadableRowRef(id = 2, kind = "LEGACY_UNREADABLE"), reported)
    }

    @Test
    fun aKindThatIsNotInTheKnownSetIsReportedWhateverItsName() = runTest {
        entry(1, kind = "INCOME")
        entry(2, kind = "FROM_A_NEWER_BUILD")

        val reported = queries.observeUnknownKind(known = setOf("INCOME", "EXPENSE", "TRANSFER")).first()

        assertEquals(UnreadableRowRef(id = 2, kind = "FROM_A_NEWER_BUILD"), reported)
    }

    @Test
    fun whenEveryKindIsKnownNothingIsReported() = runTest {
        entry(1, kind = "INCOME")
        entry(2, kind = "TRANSFER")

        assertNull(queries.observeUnknownKind(known = setOf("INCOME", "EXPENSE", "TRANSFER")).first())
    }

    @Test
    fun anAccountWithALegIsInUseWhateverTheLegRole() = runTest {
        entry(1, kind = "TRANSFER")
        accountLeg(1, "SOURCE", accountId = 1, amount = "-5")
        accountLeg(1, "DEST", accountId = 2, amount = "5")

        assertTrue(queries.isAccountInUse(1))
        assertTrue("the destination side counts too", queries.isAccountInUse(2))
        assertFalse(queries.isAccountInUse(3))
    }

    @Test
    fun anAccountIsNoLongerInUseOnceItsEntryIsDeleted() = runTest {
        entry(1)
        accountLeg(1, "ACCOUNT", accountId = 1, amount = "-5")
        sql.execSQL("DELETE FROM entries WHERE id = 1")

        assertFalse(queries.isAccountInUse(1))
    }

    @Test
    fun fillMissingRateFillsOnlyTheLegsInThatCurrencyThatHaveNoRate() = runTest {
        entry(1)
        categoryLeg(1, amount = "10", currency = "EUR", rate = null)
        entry(2)
        categoryLeg(2, amount = "20", currency = "EUR", rate = null)
        entry(3)
        categoryLeg(3, amount = "30", currency = "EUR", rate = "1.08")
        entry(4)
        categoryLeg(4, amount = "40", currency = "JPY", rate = null)
        accountLeg(4, "ACCOUNT", accountId = 1, amount = "-40", currency = "EUR")

        val filled = queries.fillMissingRate("EUR", BigDecimal("1.2"))

        assertEquals("two EUR category legs had no rate", 2, filled)
        assertEquals(
            mapOf(1L to "1.2", 2L to "1.2", 3L to "1.08", 4L to null),
            sql.textRows("SELECT entryId, rateToBase FROM entry_legs WHERE role = 'MAIN'").associate { it[0]!!.toLong() to it[1] },
        )
        assertEquals(
            "an account leg never gets a rate",
            "0",
            sql.scalar("SELECT COUNT(*) FROM entry_legs WHERE accountId IS NOT NULL AND rateToBase IS NOT NULL"),
        )
    }

    @Test
    fun fillMissingRateOnACurrencyWithNothingMissingChangesNothing() = runTest {
        entry(1)
        categoryLeg(1, amount = "30", currency = "EUR", rate = "1.08")

        assertEquals(0, queries.fillMissingRate("EUR", BigDecimal("1.2")))
        assertEquals("1.08", sql.scalar("SELECT rateToBase FROM entry_legs WHERE entryId = 1"))
    }

    @Test
    fun categoryLegsAreReadForTheRangeOnlyWithTheirStoredRate() = runTest {
        entry(1, date = 20454)
        categoryLeg(1, amount = "10.00", currency = "EUR", rate = "1.08")
        entry(2, date = 20999)
        categoryLeg(2, amount = "20.00", currency = "EUR", rate = null)

        val legs = queries.observeCategoryLegs(DateRange.ALL_TIME).first()

        assertEquals(setOf(1L, 2L), legs.map { it.entryId }.toSet())
        assertEquals(BigDecimal("1.08"), legs.single { it.entryId == 1L }.rateToBase)
        assertNull(legs.single { it.entryId == 2L }.rateToBase)
    }
}
