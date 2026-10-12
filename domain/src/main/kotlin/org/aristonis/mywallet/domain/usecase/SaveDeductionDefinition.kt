package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.NameKey
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.DeductionItemRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import java.util.Locale

/**
 * Saves a reusable rule and the expense category it books to in one transaction, so a refused rule
 * never leaves behind a category that was created only for it.
 */
class SaveDeductionDefinition(
    private val items: DeductionItemRepository,
    private val categories: CategoryRepository,
    private val createCategory: CreateCategory,
    private val saveItem: SaveDeductionItem,
    private val runner: TransactionRunner,
) {
    /** A null [categoryId] books the rule to a top-level expense category under the rule's own name. */
    suspend operator fun invoke(item: DeductionItem, categoryId: Long?): Long = runner.inTransaction {
        // Checked before the category is resolved, so a duplicate name never creates a category at all.
        val key = NameKey.of(item.name)
        if (items.observeAll().first().any { it.id != item.id && NameKey.of(it.name) == key }) {
            throw WalletException.DeductionItemNameTaken(item.name)
        }
        val resolved = categoryId?.let { chosenCategory(it) } ?: automaticCategory(item.name.trim())
        saveItem(item.copy(expenseCategoryId = resolved))
    }

    private suspend fun chosenCategory(id: Long): Long {
        val category = categories.findById(id) ?: throw WalletException.CategoryNotFound(id)
        if (category.kind != CategoryKind.EXPENSE) throw WalletException.CategoryWrongKind(id)
        return id
    }

    /** Reuses a same-named top-level expense category so repeated saves do not multiply categories. */
    private suspend fun automaticCategory(name: String): Long {
        val folded = name.lowercase(Locale.ROOT)
        return categories.observeAll().first().firstOrNull {
            it.kind == CategoryKind.EXPENSE && !it.isSubCategory && it.name.lowercase(Locale.ROOT) == folded
        }?.id ?: createCategory(name, CategoryKind.EXPENSE)
    }
}
