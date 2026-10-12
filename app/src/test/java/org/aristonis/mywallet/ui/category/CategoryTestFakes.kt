package org.aristonis.mywallet.ui.category

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CategoryRoleRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.usecase.CreateCategory

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

    /** The next this-many reads fail, then reads serve again (a read that fails once). */
    var failedReadsLeft = 0

    /** Once the failing reads are used up, reads never answer: the state a retry is in while it reads. */
    var hangsAfterFailures = false

    override fun observeAll(): Flow<List<Category>> = flow {
        if (failedReadsLeft > 0) {
            failedReadsLeft--
            throw IllegalStateException("a stored row could not be read")
        }
        if (hangsAfterFailures) awaitCancellation()
        emitAll(items)
    }
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

    override suspend fun bucketFor(kind: CategoryKind): Long {
        val key = Category.uncategorizedKeyFor(kind)
        items.value.firstOrNull { it.systemKey == key }?.let { return it.id }
        return upsert(Category(name = key, kind = kind, systemKey = key))
    }
}

internal fun categoryCreator(categories: CategoryRepository): CreateCategory {
    val roles = object : CategoryRoleRepository {
        private var bindings = emptyMap<CategoryRole, Long>()
        override suspend fun categoryFor(role: CategoryRole): Long? = bindings[role]
        override suspend fun bindIfMissing(role: CategoryRole, categoryId: Long): Boolean {
            if (role in bindings) return false
            bindings = bindings + (role to categoryId)
            return true
        }
    }
    val runner = object : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }
    return CreateCategory(categories, roles, runner)
}
