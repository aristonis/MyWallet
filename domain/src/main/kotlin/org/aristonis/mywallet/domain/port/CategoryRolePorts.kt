package org.aristonis.mywallet.domain.port

import org.aristonis.mywallet.domain.model.CategoryRole

interface CategoryRoleRepository {

    /**
     * The category [role] files its legs under, or null once the owner deleted that category: the
     * binding goes with it, so a null means "use the kind's Uncategorized bucket".
     */
    suspend fun categoryFor(role: CategoryRole): Long?

    /** Binds only a role without a valid category; an existing valid binding is preserved. */
    suspend fun bindIfMissing(role: CategoryRole, categoryId: Long): Boolean
}
