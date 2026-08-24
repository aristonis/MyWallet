package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.port.CategoryRepository

/**
 * Deletes a category and re-points the transactions that referenced it, returning how many of them
 * changed so the screen can name a real number in its confirmation.
 *
 * The reassignment itself spans two tables and has to be atomic, so it is one call into the adapter
 * rather than a read-then-write sequence here — see [CategoryRepository.deleteAndReassign]. What
 * this use-case owns is the guards, and picking the fallback bucket of the category's OWN kind:
 * sending expenses to the income bucket would balance perfectly and be wrong everywhere.
 *
 * The Uncategorized buckets themselves cannot be deleted — the check runs before the adapter is
 * touched, since deleting the bucket would leave the next delete with nowhere to move its
 * transactions.
 */
class DeleteCategory(
    private val categories: CategoryRepository,
) {
    suspend operator fun invoke(id: Long): Int {
        val existing = categories.findById(id) ?: throw WalletException.CategoryNotFound(id)
        if (existing.isSystem) throw WalletException.SystemCategoryProtected(id)

        return categories.deleteAndReassign(
            categoryId = id,
            kind = existing.kind,
            fallbackKey = Category.uncategorizedKeyFor(existing.kind),
        )
    }
}
