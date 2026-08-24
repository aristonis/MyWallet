package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.port.CategoryRepository

/**
 * Creates a category, or a sub-category when [parentId] is given. Returns the new id.
 *
 * Every guard here is also an index in the database. They are stated in the core so the failure the
 * user sees is a typed domain error rather than whatever SQLite raised, and so a caller that is not
 * the category screen — a future import, say — gets the same rules for free.
 */
class CreateCategory(
    private val categories: CategoryRepository,
) {
    suspend operator fun invoke(name: String, kind: CategoryKind, parentId: Long? = null): Long {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "category name must not be blank" }

        if (parentId != null) {
            val parent = categories.findById(parentId) ?: throw WalletException.CategoryNotFound(parentId)
            // An expense under an income parent would show up in whichever report walked the tree
            // from the parent down, and be missing from the other.
            if (parent.kind != kind) throw WalletException.CategoryKindMismatch(parentId)
            // Two levels is a hard cap. The picker only offers parents, but a deeper tree would
            // still break every breakdown that assumes a child rolls up in exactly one step.
            if (parent.isSubCategory) throw WalletException.CategoryDepthExceeded(parentId)
        }

        requireNameNotReserved(trimmed)
        categories.observeAll().first().requireNameFreeAmongSiblings(trimmed, kind, parentId)

        return categories.upsert(Category(name = trimmed, kind = kind, parentId = parentId))
    }
}
