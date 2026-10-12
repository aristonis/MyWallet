package org.aristonis.mywallet.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.data.db.DeductionDao
import org.aristonis.mywallet.data.db.DeductionItemAutoCategoryEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.db.toDomain
import org.aristonis.mywallet.data.db.toEntity
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.port.DeductionItemRepository

/**
 * Persists reusable deduction rules and their default-category links. An item and its links are
 * always written in one database transaction, so readers never observe a partially replaced rule.
 * The unique normalized-name index remains the final guard if two writers race past the lookup.
 */
class RoomDeductionItemRepository(
    private val database: WalletDatabase,
    private val dao: DeductionDao = database.deductionDao(),
) : DeductionItemRepository {

    override fun observeAll(): Flow<List<DeductionItem>> = dao.observeAllItemsWithCategories().map { rows ->
        rows.map { row -> row.item.toDomain(row.autoCategories.map { it.incomeCategoryId }.toSet()) }
    }

    override suspend fun findById(id: Long): DeductionItem? = database.withTransaction {
        val row = dao.findItem(id) ?: return@withTransaction null
        row.toDomain(dao.autoCategoryIds(id).toSet())
    }

    /**
     * Inserts a new rule (id 0) or replaces its current row and all category links atomically. A
     * positive id that is not stored is refused, so a rule deleted elsewhere is not silently re-created.
     *
     * @throws WalletException.DeductionItemNotFound when a positive id has no stored row.
     * @throws WalletException.DeductionItemNameTaken when another rule has the same normalized name.
     */
    override suspend fun upsert(item: DeductionItem): Long {
        val row = item.toEntity()
        return try {
            database.withTransaction {
                val existing = dao.findItemByNameKey(row.nameKey)
                if (existing != null && existing.id != row.id) {
                    throw WalletException.DeductionItemNameTaken(row.name)
                }

                val id = when {
                    row.id == 0L -> dao.insertItem(row)
                    dao.findItem(row.id) != null -> {
                        dao.updateItem(row)
                        row.id
                    }
                    else -> throw WalletException.DeductionItemNotFound(row.id)
                }
                dao.clearAutoCategories(id)
                if (item.autoAddCategoryIds.isNotEmpty()) {
                    dao.insertAutoCategoriesForItem(
                        item.autoAddCategoryIds.sorted().map { categoryId ->
                            DeductionItemAutoCategoryEntity(itemId = id, incomeCategoryId = categoryId)
                        },
                    )
                }
                id
            }
        } catch (e: SQLiteConstraintException) {
            val nameHolder = dao.findItemByNameKey(row.nameKey)
            if (nameHolder != null && nameHolder.id != row.id) {
                throw WalletException.DeductionItemNameTaken(row.name, e)
            }
            throw e
        }
    }

    /** Refuses deletion while a saved income line refers to the item. */
    override suspend fun delete(id: Long) {
        try {
            database.withTransaction {
                if (dao.isItemInUse(id)) throw WalletException.DeductionItemInUse(id)
                dao.deleteItem(id)
            }
        } catch (e: SQLiteConstraintException) {
            // The FK RESTRICT is the backstop for a line inserted between a check and deletion.
            if (dao.isItemInUse(id)) throw WalletException.DeductionItemInUse(id)
            throw e
        }
    }

    override suspend fun isInUse(id: Long): Boolean = dao.isItemInUse(id)
}
