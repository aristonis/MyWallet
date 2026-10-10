package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CategoryRoleRepository

/**
 * Picks the category for a leg that belongs to a [CategoryRole]. Call it inside the transaction
 * runner: the bucket fallback creates a row.
 */
class RoleCategories(
    private val roles: CategoryRoleRepository,
    private val categories: CategoryRepository,
) {

    /**
     * The category picked on the form wins for that entry only (the role is not re-bound), then the
     * role's own category, then the Uncategorized bucket of the role's kind.
     */
    suspend fun categoryFor(role: CategoryRole, picked: Long?): Long =
        picked ?: roles.categoryFor(role) ?: categories.bucketFor(role.kind)
}
