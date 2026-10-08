package org.aristonis.mywallet.ui.category

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.aristonis.mywallet.domain.model.DateRange
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.TransactionRepository

/**
 * A writable category store for the manage screen. The reassignment itself belongs to Room, so the
 * fake models only what the screen can observe: the doomed rows disappear and a count comes back.
 */
internal class FakeManageCategoryRepository(initial: List<Category> = emptyList()) : CategoryRepository {
    private val items = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    val stored: List<Category> get() = items.value

    /** What the next delete reports back, standing in for the rows Room would re-file. */
    var affectedRows: Int = 0

    /** Set to make the next write fail, so the screen's error path can be driven. */
    var failWith: WalletException? = null

    override fun observeAll(): Flow<List<Category>> = items
    override suspend fun findById(id: Long): Category? = items.value.firstOrNull { it.id == id }

    override suspend fun upsert(category: Category): Long {
        failWith?.let { throw it }
        val id = if (category.id == 0L) nextId++ else category.id
        items.value = items.value.filterNot { it.id == id } + category.copy(id = id)
        return id
    }

    override suspend fun deleteAndReassign(categoryId: Long, kind: CategoryKind, fallbackKey: String): Int {
        failWith?.let { throw it }
        val doomed = items.value.filter { it.id == categoryId || it.parentId == categoryId }.map { it.id }.toSet()
        items.value = items.value.filterNot { it.id in doomed }
        return affectedRows
    }
}

internal class FakeCategoryTransactionRepository(initial: List<Transaction> = emptyList()) : TransactionRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Transaction>> = items
    override fun observeBetween(range: DateRange): Flow<List<Transaction>> =
        items.map { all -> all.filter { it.date in range } }
    override suspend fun findById(id: Long): Transaction? = items.value.firstOrNull { it.id == id }
    override suspend fun add(transaction: Transaction): Long = error("the manage screen does not add transactions")
    override suspend fun update(transaction: Transaction) = error("the manage screen does not edit transactions")
    override suspend fun delete(id: Long) = error("the manage screen does not delete transactions")
}
