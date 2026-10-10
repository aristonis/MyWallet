package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.NameKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The Room debt adapter against an in-memory database. A debt row that no entry points at is the
 * "hidden" row of the app; every row made here starts that way.
 */
@RunWith(AndroidJUnit4::class)
class RoomDebtRepositoryTest {

    private lateinit var db: WalletDatabase
    private lateinit var repository: RoomDebtRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomDebtRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend inline fun <reified T : Throwable> failureOf(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("expected ${T::class.simpleName} but nothing was thrown")
    }

    private suspend fun rows() = db.debtDao().getAll()

    @Test
    fun getOrCreateReturnsTheSameRowForTheSameKeySideAndCurrency() = runTest {
        val first = repository.getOrCreate(" ali ", DebtSide.I_OWE, "USD")
        val second = repository.getOrCreate("Ali", DebtSide.I_OWE, "USD")

        assertEquals(first.id, second.id)
        assertEquals(1, rows().size)
        assertEquals("ali", second.counterparty)
        assertEquals("ali", rows().single().counterparty)
    }

    @Test
    fun anotherSideOrCurrencyIsANewRow() = runTest {
        val base = repository.getOrCreate("Ali", DebtSide.I_OWE, "USD")
        val otherSide = repository.getOrCreate("Ali", DebtSide.OWED_TO_ME, "USD")
        val otherCurrency = repository.getOrCreate("Ali", DebtSide.I_OWE, "EUR")

        assertEquals(3, setOf(base.id, otherSide.id, otherCurrency.id).size)
        assertEquals(3, rows().size)
    }

    @Test
    fun aBlankNameFailsBeforeAnySqlAndLeavesTheTableEmpty() = runTest {
        failureOf<WalletException.NameBlank> { repository.getOrCreate("  \u200F ", DebtSide.I_OWE, "USD") }

        assertEquals(0, rows().size)
    }

    @Test
    fun renameOntoATakenKeySideAndCurrencyIsRefusedWithDebtNameTaken() = runTest {
        repository.getOrCreate("Ali", DebtSide.I_OWE, "USD")
        val other = repository.getOrCreate("Omar", DebtSide.I_OWE, "USD")

        val thrown = failureOf<WalletException.DebtNameTaken> { repository.rename(other.id, " ALI ") }

        assertEquals("ALI", thrown.counterparty.trim())
        assertEquals("Omar", repository.findById(other.id)?.counterparty)
    }

    @Test
    fun renameOntoAHiddenEmptiedRowIsRefusedToo() = runTest {
        // The taken row has no legs at all: it is the hidden leftover of a debt whose entries were deleted.
        val hidden = repository.getOrCreate("Ali", DebtSide.OWED_TO_ME, "EUR")
        val other = repository.getOrCreate("Omar", DebtSide.OWED_TO_ME, "EUR")

        failureOf<WalletException.DebtNameTaken> { repository.rename(other.id, "ali") }

        assertEquals("Ali", repository.findById(hidden.id)?.counterparty)
        assertEquals("Omar", repository.findById(other.id)?.counterparty)
    }

    @Test
    fun aCaseOnlyRenamePassesAndKeepsTheId() = runTest {
        val debt = repository.getOrCreate("ali", DebtSide.I_OWE, "USD")

        repository.rename(debt.id, "ALI")

        val read = repository.findById(debt.id)
        assertEquals("ALI", read?.counterparty)
        assertEquals(debt.id, read?.id)
        assertEquals(1, rows().size)
    }

    @Test
    fun renameOntoTheSameKeyOnAnotherSideIsAllowed() = runTest {
        repository.getOrCreate("Ali", DebtSide.OWED_TO_ME, "USD")
        val mine = repository.getOrCreate("Omar", DebtSide.I_OWE, "USD")

        repository.rename(mine.id, "Ali")

        assertEquals("Ali", repository.findById(mine.id)?.counterparty)
        assertEquals(2, rows().size)
    }

    @Test
    fun renameToABlankNameIsRefusedAndKeepsTheOldName() = runTest {
        val debt = repository.getOrCreate("Ali", DebtSide.I_OWE, "USD")

        failureOf<WalletException.NameBlank> { repository.rename(debt.id, "   ") }

        assertEquals("Ali", repository.findById(debt.id)?.counterparty)
    }

    @Test
    fun counterpartyNamesGivesOneNamePerKeySortedByKey() = runTest {
        repository.getOrCreate("Alina", DebtSide.I_OWE, "USD")
        repository.getOrCreate("Ali", DebtSide.I_OWE, "USD")
        repository.getOrCreate("ALI", DebtSide.OWED_TO_ME, "USD")
        repository.getOrCreate("Alice", DebtSide.I_OWE, "EUR")
        repository.getOrCreate("Bob", DebtSide.I_OWE, "USD")

        val names = repository.counterpartyNames("al")

        assertEquals(listOf("ali", "alice", "alina"), names.map { NameKey.of(it).value })
    }

    @Test
    fun theCounterpartyPrefixIsMatchedOnItsKeyNotItsCase() = runTest {
        repository.getOrCreate("Alice", DebtSide.I_OWE, "USD")

        assertEquals(listOf("Alice"), repository.counterpartyNames(" ALI"))
    }

    @Test
    fun aPercentSignInThePrefixMatchesLiterally() = runTest {
        repository.getOrCreate("5%a", DebtSide.I_OWE, "USD")
        repository.getOrCreate("5xa", DebtSide.I_OWE, "USD")

        assertEquals(listOf("5%a"), repository.counterpartyNames("5%"))
    }

    @Test
    fun anUnderscoreInThePrefixMatchesLiterally() = runTest {
        repository.getOrCreate("a_b", DebtSide.I_OWE, "USD")
        repository.getOrCreate("axb", DebtSide.I_OWE, "USD")

        assertEquals(listOf("a_b"), repository.counterpartyNames("a_"))
    }

    @Test
    fun aBlankPrefixGivesEveryNameOnce() = runTest {
        repository.getOrCreate("Bob", DebtSide.I_OWE, "USD")
        repository.getOrCreate("Ali", DebtSide.I_OWE, "USD")
        repository.getOrCreate("ali", DebtSide.OWED_TO_ME, "USD")

        val names = repository.counterpartyNames("   ")

        assertEquals(listOf("ali", "bob"), names.map { NameKey.of(it).value })
    }

    @Test
    fun updateDetailsSavesTheDueDateAndNoteAndNullClearsThem() = runTest {
        val debt = repository.getOrCreate("Ali", DebtSide.I_OWE, "USD")

        repository.updateDetails(debt.id, LocalDate.of(2026, 5, 1), "for rent")
        val saved = repository.findById(debt.id)
        assertEquals(LocalDate.of(2026, 5, 1), saved?.dueDate)
        assertEquals("for rent", saved?.note)

        repository.updateDetails(debt.id, null, null)
        val cleared = repository.findById(debt.id)
        assertNull(cleared?.dueDate)
        assertNull(cleared?.note)
    }

    @Test
    fun findByIdGivesNullForAnUnknownId() = runTest {
        assertNull(repository.findById(999))
    }

    @Test
    fun observeAllEmitsAgainAfterAChange() = runBlocking {
        val seen = Channel<List<Long>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            repository.observeAll().collect { seen.send(it.map { debt -> debt.id }) }
        }
        try {
            assertEquals(emptyList<Long>(), withTimeout(WAIT_MS) { seen.receive() })

            val debt = repository.getOrCreate("Ali", DebtSide.I_OWE, "USD")

            assertEquals(listOf(debt.id), withTimeout(WAIT_MS) { seen.receive() })
        } finally {
            collector.cancelAndJoin()
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
