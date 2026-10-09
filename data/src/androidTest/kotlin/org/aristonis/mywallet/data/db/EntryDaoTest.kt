package org.aristonis.mywallet.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The reads return one row per leg, and the entry above them is rebuilt from consecutive rows, so
 * the order of the legs inside one entry is part of the contract: by position, then by id. Without
 * an explicit order SQLite hands them back in the order of whichever index it joined through (the
 * role, here), which is not the order they were written in.
 *
 * The legs are written by raw SQL, in an order that matches neither their positions nor the
 * alphabetical order of their roles, so only an explicit order can produce the expected result.
 */
@RunWith(AndroidJUnit4::class)
class EntryDaoTest {

    private lateinit var db: WalletDatabase

    private val day = LocalDate.of(2026, 8, 10)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val sql get() = db.openHelper.writableDatabase

    private fun insertEntry(id: Long, date: LocalDate) =
        sql.execSQL("INSERT INTO entries (id, kind, date) VALUES (?, 'EXPENSE', ?)", arrayOf<Any?>(id, date.toEpochDay()))

    private fun insertLeg(id: Long, entryId: Long, role: String, position: Int) = sql.execSQL(
        "INSERT INTO entry_legs (id, entryId, role, position, amount, currencyCode) VALUES (?, ?, ?, ?, '1.00', 'USD')",
        arrayOf<Any?>(id, entryId, role, position),
    )

    /**
     * Written first to last: id 10 ACCOUNT at 2, id 11 MAIN at 0, id 12 FEE at 1, id 13 DEST at 0.
     * By position then id they read 11, 13, 12, 10. By role they would read 10, 13, 12, 11; by rowid
     * 10, 11, 12, 13.
     */
    private fun insertLegsOutOfOrder(entryId: Long, firstLegId: Long = 10) {
        insertLeg(firstLegId, entryId, "ACCOUNT", 2)
        insertLeg(firstLegId + 1, entryId, "MAIN", 0)
        insertLeg(firstLegId + 2, entryId, "FEE", 1)
        insertLeg(firstLegId + 3, entryId, "DEST", 0)
    }

    @Test
    fun findByIdReadsTheLegsOfOneEntryByPositionThenId() = runTest {
        insertEntry(1, day)
        insertLegsOutOfOrder(entryId = 1)

        val rows = db.entryDao().findById(1)

        assertEquals(listOf(11L, 13L, 12L, 10L), rows.map { it.legId })
        assertEquals(listOf(0, 0, 1, 2), rows.map { it.position })
        assertEquals(listOf("MAIN", "DEST", "FEE", "ACCOUNT"), rows.map { it.role })
    }

    @Test
    fun twoLegsAtOnePositionComeBackInIdOrderNotRoleOrder() = runTest {
        insertEntry(1, day)
        insertLeg(20, 1, "MAIN", 0)
        insertLeg(21, 1, "ACCOUNT", 0)

        val rows = db.entryDao().findById(1)

        assertEquals(listOf(20L, 21L), rows.map { it.legId })
    }

    @Test
    fun observeBetweenReadsEveryEntrysLegsByPositionThenIdAndKeepsEntriesNewestFirst() = runTest {
        insertEntry(1, day)
        insertEntry(2, day.plusDays(3))
        insertLegsOutOfOrder(entryId = 1, firstLegId = 10)
        insertLegsOutOfOrder(entryId = 2, firstLegId = 30)

        val rows = db.entryDao().observeBetween(day, day.plusDays(3)).first()

        assertEquals(
            listOf(2L to 31L, 2L to 33L, 2L to 32L, 2L to 30L, 1L to 11L, 1L to 13L, 1L to 12L, 1L to 10L),
            rows.map { it.entryId to it.legId },
        )
    }

    @Test
    fun anEntryWithNoLegsStillComesBackAsOneRowWithEmptyLegColumns() = runTest {
        insertEntry(1, day)

        val rows = db.entryDao().findById(1)

        assertEquals(1, rows.size)
        assertEquals(null, rows.single().legId)
        assertEquals(null, rows.single().position)
    }
}
