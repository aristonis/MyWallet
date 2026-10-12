package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CategoryRoleRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import java.util.Locale

/**
 * Creates a category, or a sub-category when [parentId] is given. Returns the new id.
 *
 * Every guard here is also an index in the database. They are stated in the core so the failure the
 * user sees is a typed domain error rather than whatever SQLite raised, and so a caller that is not
 * the category screen — a future import, say — gets the same rules for free.
 */
class CreateCategory(
    private val categories: CategoryRepository,
    private val roles: CategoryRoleRepository,
    private val runner: TransactionRunner,
) {
    suspend operator fun invoke(name: String, kind: CategoryKind, parentId: Long? = null): Long =
        runner.inTransaction { create(name, kind, parentId) }

    private suspend fun create(name: String, kind: CategoryKind, parentId: Long?): Long {
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

        val id = categories.upsert(Category(name = trimmed, kind = kind, parentId = parentId))
        if (parentId == null) {
            val normalized = trimmed.lowercase(Locale.ROOT)
            CategoryRole.entries.firstOrNull {
                it.kind == kind && it.literalName.lowercase(Locale.ROOT) == normalized
            }?.let { roles.bindIfMissing(it, id) }
        }
        return id
    }
}
