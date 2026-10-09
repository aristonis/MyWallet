package org.aristonis.mywallet.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.repo.RoomLedgerQueries
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.AccountLeg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An account leg amount that does not parse is bad stored data, reported as the entry it belongs to.
 * Two real ways to get there: "12,50" typed with a decimal comma, and "--5", which a legacy negative
 * amount became when the upgrade put a minus in front of it.
 *
 * Assumed behavior of `RoomLedgerQueries.observeAccountLegs()`: the flow fails with
 * `WalletException.UnreadableRow(entryId, entryKind)` naming the entry that holds the bad leg
 * (found by a lookup on the failure path, since the balance projection itself carries no entry id),
 * with the parse error as its cause. It never fails with a NumberFormatException.
 */
@RunWith(AndroidJUnit4::class)
class UnreadableAmountReadTest {

    private lateinit var db: WalletDatabase
    private lateinit var queries: RoomLedgerQueries

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java)
            .allowMainThreadQueries().build()
        queries = RoomLedgerQueries(db.ledgerQueryDao())
        sql.execSQL(
            "INSERT INTO accounts (id, name, typeKey, currencyCode, openingBalanceAmount, archived, sortOrder) " +
                "VALUES (1, 'Cash', 'cash', 'USD', '0', 0, 0)",
        )
    }

    @After
    fun tearDown() = db.close()

    private val sql get() = db.openHelper.writableDatabase

    private fun entryWithAccountLeg(id: Long, kind: String, amount: String) {
        sql.execSQL("INSERT INTO entries (id, kind, date) VALUES (?, ?, 20454)", arrayOf<Any?>(id, kind))
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) VALUES (?, 'ACCOUNT', 0, 1, ?, 'USD')",
            arrayOf<Any?>(id, amount),
        )
    }

    private suspend fun failureOfBalanceRead(): Throwable? = runCatching { queries.observeAccountLegs().first() }.exceptionOrNull()

    @Test
    fun anAmountWithADecimalCommaIsUnreadableAndNamesItsEntry() = runTest {
        entryWithAccountLeg(1, "INCOME", "100.00")
        entryWithAccountLeg(2, "EXPENSE", "12,50")
        entryWithAccountLeg(3, "EXPENSE", "-1.00")

        val failure = failureOfBalanceRead()

        assertTrue("expected UnreadableRow, got $failure", failure is WalletException.UnreadableRow)
        assertEquals(2L, (failure as WalletException.UnreadableRow).id)
        assertEquals("EXPENSE", failure.kind)
    }

    @Test
    fun aDoubleMinusFromALegacyNegativeAmountIsUnreadableAndNamesItsEntry() = runTest {
        entryWithAccountLeg(1, "INCOME", "100.00")
        entryWithAccountLeg(4, "INCOME", "--5")

        val failure = failureOfBalanceRead()

        assertTrue("expected UnreadableRow, got $failure", failure is WalletException.UnreadableRow)
        assertEquals(4L, (failure as WalletException.UnreadableRow).id)
        assertEquals("INCOME", failure.kind)
    }

    @Test
    fun theFailureIsNeverABareNumberFormatException() = runTest {
        entryWithAccountLeg(5, "EXPENSE", "12,50")

        val failure = failureOfBalanceRead()

        assertTrue(failure !is NumberFormatException)
        assertTrue(failure?.cause is NumberFormatException)
    }

    @Test
    fun legsThatParseAreStillReadWhenNothingIsBad() = runTest {
        entryWithAccountLeg(1, "INCOME", "100.00")
        entryWithAccountLeg(2, "EXPENSE", "-0.50")

        val legs = queries.observeAccountLegs().first()

        assertEquals(
            setOf(AccountLeg(1, Money.of("100.00", "USD")), AccountLeg(1, Money.of("-0.50", "USD"))),
            legs.toSet(),
        )
    }
}
