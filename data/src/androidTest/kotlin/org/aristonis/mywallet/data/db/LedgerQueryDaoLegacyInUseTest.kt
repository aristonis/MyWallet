package org.aristonis.mywallet.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.repo.RoomLedgerQueries
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An account that only a set-aside raw copy of an old row points at is still in use: deleting it, or
 * changing its currency, would break the copy that holds the owner's data.
 *
 * Assumed behaviour: `isAccountInUse` also answers true when `legacy_unreadable_rows.primaryAccountId`
 * or `secondaryAccountId` equals the account id. The existing `ACCOUNT_IN_USE_SQL` constant keeps
 * its name, one `:accountId` parameter, an EXISTS shape and the account-leg index on the leg branch.
 */
@RunWith(AndroidJUnit4::class)
class LedgerQueryDaoLegacyInUseTest {

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

    private fun legacyRow(id: Long, primaryAccountId: Long, secondaryAccountId: Long?) {
        sql.execSQL(
            "INSERT INTO legacy_unreadable_rows " +
                "(id, type, date, note, primaryAccountId, primaryAmount, primaryCurrency, categoryId, subCategoryId, " +
                "secondaryAccountId, secondaryAmount, secondaryCurrency, rateUsed, reason) " +
                "VALUES (?, 'TRANSFER', 20454, NULL, ?, '5', 'USD', NULL, NULL, ?, '5', 'USD', '1', 'unreadable')",
            arrayOf<Any?>(id, primaryAccountId, secondaryAccountId),
        )
    }

    @Test
    fun anAccountOnlyALegacyRowsPrimaryAccountPointsAtIsInUse() = runTest {
        legacyRow(id = 1, primaryAccountId = 4, secondaryAccountId = null)

        assertTrue(queries.isAccountInUse(4))
    }

    @Test
    fun anAccountOnlyALegacyRowsSecondaryAccountPointsAtIsInUse() = runTest {
        legacyRow(id = 1, primaryAccountId = 4, secondaryAccountId = 5)

        assertTrue(queries.isAccountInUse(5))
    }

    @Test
    fun anAccountNoLegAndNoLegacyRowPointsAtIsNotInUse() = runTest {
        legacyRow(id = 1, primaryAccountId = 4, secondaryAccountId = 5)

        assertFalse(queries.isAccountInUse(6))
    }

    @Test
    fun anAccountWithOnlyALegIsStillInUse() = runTest {
        sql.execSQL("INSERT INTO entries (id, kind, date) VALUES (1, 'EXPENSE', 20454)")
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) " +
                "VALUES (1, 'ACCOUNT', 0, 7, '-5', 'USD')",
        )

        assertTrue(queries.isAccountInUse(7))
    }

    @Test
    fun anAccountIsNoLongerInUseOnceItsLegacyRowIsRemoved() = runTest {
        legacyRow(id = 1, primaryAccountId = 4, secondaryAccountId = null)
        sql.execSQL("DELETE FROM legacy_unreadable_rows")

        assertFalse(queries.isAccountInUse(4))
    }

    // --- The set of accounts in use, as the Manage Accounts list reads it. ---

    private fun account(id: Long) {
        sql.execSQL(
            "INSERT INTO accounts (id, name, typeKey, currencyCode, openingBalanceAmount, archived, sortOrder) " +
                "VALUES (?, 'Account', 'cash', 'USD', '0', 0, 0)",
            arrayOf<Any?>(id),
        )
    }

    private fun legOn(entryId: Long, accountId: Long) {
        sql.execSQL("INSERT INTO entries (id, kind, date) VALUES (?, 'EXPENSE', 20454)", arrayOf<Any?>(entryId))
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) " +
                "VALUES (?, 'ACCOUNT', 0, ?, '-5', 'USD')",
            arrayOf<Any?>(entryId, accountId),
        )
    }

    @Test
    fun theAccountsInUseAreTheOnesIsAccountInUseAnswersTrueFor() = runTest {
        (4L..8L).forEach(::account)
        legacyRow(id = 1, primaryAccountId = 4, secondaryAccountId = 5)
        legOn(entryId = 1, accountId = 7)

        val inUse = queries.observeAccountsInUse().first()

        assertEquals(setOf(4L, 5L, 7L), inUse)
        (4L..8L).forEach { id -> assertEquals("account $id", queries.isAccountInUse(id), id in inUse) }
    }

    @Test
    fun anAccountLeavesTheInUseSetOnceNothingPointsAtItAnyMore() = runTest {
        account(4)
        account(7)
        legacyRow(id = 1, primaryAccountId = 4, secondaryAccountId = null)
        legOn(entryId = 1, accountId = 7)

        sql.execSQL("DELETE FROM legacy_unreadable_rows")
        sql.execSQL("DELETE FROM entries WHERE id = 1")

        assertEquals(emptySet<Long>(), queries.observeAccountsInUse().first())
    }
}
