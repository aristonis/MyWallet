package org.aristonis.mywallet.data.golden

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.repo.RoomEntryRepository
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.TransactionFilter
import org.aristonis.mywallet.domain.model.TypeGroup
import org.aristonis.mywallet.domain.port.EntryKey
import org.aristonis.mywallet.domain.service.TransactionFilterMatch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class FilteredPageAgreementTest {
    @Test fun sqlAndMemoryAgreeForEveryChipAccountAndTopLevelCategoryOnMigratedSeed() = runTest(timeout = 10.minutes) {
        withFilteredSeed(ApplicationProvider.getApplicationContext<Context>(), "filtered-agreement.db") { db ->
            val repository = RoomEntryRepository(db, db.entryDao())
            val all = repository.observeAll().first()
            fun ids(query: String) = db.openHelper.writableDatabase.query(query).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
            val accounts = listOf<Long?>(null) + ids("SELECT id FROM accounts ORDER BY id")
            val categories = listOf<Long?>(null) + ids("SELECT id FROM categories WHERE parentId=0 ORDER BY id")
            for (type in TypeGroup.entries) for (account in accounts) for (category in categories) {
                val filter = TransactionFilter(
                    types = if (type == TypeGroup.ALL) emptySet() else setOf(type),
                    accountIds = setOfNotNull(account), categoryIds = setOfNotNull(category),
                )
                val expected = all.filter { TransactionFilterMatch.matches(it, filter) }.map { it.id }
                val actual = walk(repository, filter).map { it.id }
                assertEquals("filter=$filter", expected, actual)
            }
            val multi = TransactionFilter(types = setOf(TypeGroup.INCOME, TypeGroup.EXPENSE),
                accountIds = accounts.filterNotNull().take(2).toSet(),
                categoryIds = categories.filterNotNull().take(3).toSet())
            assertEquals(all.filter { TransactionFilterMatch.matches(it, multi) }.map { it.id },
                walk(repository, multi).map { it.id })
            assertEquals(all, walk(repository, TransactionFilter()))
        }
    }

    private suspend fun walk(repository: RoomEntryRepository, filter: TransactionFilter): List<Transaction> {
        val result = mutableListOf<Transaction>()
        var key: EntryKey? = null
        do {
            val page = repository.observePage(DateRange.ALL_TIME, key, PAGE_SIZE, filter).first()
            result += page
            key = page.lastOrNull()?.let { EntryKey(it.date, it.id) }
        } while (page.size == PAGE_SIZE)
        return result
    }

    private companion object { const val PAGE_SIZE = 101 }
}
