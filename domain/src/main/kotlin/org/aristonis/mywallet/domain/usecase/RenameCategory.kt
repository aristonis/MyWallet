package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.port.CategoryRepository

/**
 * Renames a category in place. Only the name changes: `copy` carries the kind, the parent and the
 * system key forward, so a rename can never quietly re-parent a row or flip it between income and
 * expense.
 *
 * The two Uncategorized buckets are refused. Their stored name is a placeholder that no screen ever
 * shows — the label is keyed off the system key instead — so a rename would write text nobody would
 * see and leave the bucket looking unchanged. Refusing says so plainly instead of accepting an edit
 * with no effect.
 */
class RenameCategory(
    private val categories: CategoryRepository,
) {
    suspend operator fun invoke(id: Long, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "category name must not be blank" }

        val existing = categories.findById(id) ?: throw WalletException.CategoryNotFound(id)
        if (existing.isSystem) throw WalletException.SystemCategoryProtected(id)

        requireNameNotReserved(trimmed)
        categories.observeAll().first()
            .requireNameFreeAmongSiblings(trimmed, existing.kind, existing.parentId, excludingId = id)

        categories.upsert(existing.copy(name = trimmed))
    }
}
