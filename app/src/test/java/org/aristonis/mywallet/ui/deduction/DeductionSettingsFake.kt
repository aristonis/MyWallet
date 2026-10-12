package org.aristonis.mywallet.ui.deduction

import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.*
import org.aristonis.mywallet.domain.port.DeductionItemRepository

internal fun rule() = DeductionItem(1, "Tax", DeductionMode.PERCENT, BigDecimal.TEN,
    timing = DeductionTiming.LATER, expenseCategoryId = 1, autoAddCategoryIds = setOf(2))

internal class DeductionSettingsFake(initial: List<DeductionItem> = emptyList()) : DeductionItemRepository {
    val rows = MutableStateFlow(initial)
    var used = emptySet<Long>()
    var saveFailure: Exception? = null
    var deleteFailure: Exception? = null
    var readFailure: Exception? = null
    override fun observeAll() = flow {
        readFailure?.let { throw it }
        emitAll(rows)
    }
    override suspend fun findById(id: Long) = rows.value.find { it.id == id }
    override suspend fun upsert(item: DeductionItem): Long {
        saveFailure?.let { throw it }
        if (rows.value.any { it.id != item.id && NameKey.of(it.name) == NameKey.of(item.name) }) {
            throw WalletException.DeductionItemNameTaken(item.name)
        }
        val id = item.id.takeIf { it > 0 } ?: ((rows.value.maxOfOrNull { it.id } ?: 0) + 1)
        rows.value = rows.value.filterNot { it.id == id } + item.copy(id = id)
        return id
    }
    override suspend fun delete(id: Long) {
        deleteFailure?.let { throw it }
        rows.value = rows.value.filterNot { it.id == id }
    }
    override suspend fun isInUse(id: Long) = id in used
}

internal class DeductionCategories(initial: List<Category>) : org.aristonis.mywallet.domain.port.CategoryRepository {
    val rows = MutableStateFlow(initial)
    var readFailure: Exception? = null
    var hiddenMissingIds = emptySet<Long>()
    override fun observeAll() = flow {
        readFailure?.let { throw it }
        emitAll(rows)
    }
    override suspend fun findById(id: Long) = rows.value.find { it.id == id && id !in hiddenMissingIds }
    override suspend fun upsert(category: Category): Long {
        val id = category.id.takeIf { it > 0 } ?: ((rows.value.maxOfOrNull { it.id } ?: 0) + 1)
        rows.value = rows.value.filterNot { it.id == id } + category.copy(id = id)
        return id
    }
    override suspend fun deleteAndReassign(categoryId: Long, kind: CategoryKind, fallbackKey: String) = error("unused")
    override suspend fun bucketFor(kind: CategoryKind): Long = error("unused")
}

internal class DeductionRoles : org.aristonis.mywallet.domain.port.CategoryRoleRepository {
    override suspend fun categoryFor(role: CategoryRole): Long? = null
    override suspend fun bindIfMissing(role: CategoryRole, categoryId: Long) = false
}

internal class DeductionRunner(private val categories: DeductionCategories, private val items: DeductionSettingsFake) :
    org.aristonis.mywallet.domain.port.TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        val oldCategories = categories.rows.value
        val oldItems = items.rows.value
        return try { block() } catch (error: Throwable) {
            categories.rows.value = oldCategories
            items.rows.value = oldItems
            throw error
        }
    }
}
