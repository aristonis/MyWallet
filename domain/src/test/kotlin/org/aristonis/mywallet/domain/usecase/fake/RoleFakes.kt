package org.aristonis.mywallet.domain.usecase.fake

import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.port.CategoryRoleRepository

/** Role bindings held in memory. A role absent from [bindings] is one whose category was deleted. */
class FakeCategoryRoleRepository(initial: Map<CategoryRole, Long> = emptyMap()) : CategoryRoleRepository {
    private var bindings = initial.toMap()

    /** The bindings as they stand, for asserting that nothing was re-bound. */
    val stored: Map<CategoryRole, Long> get() = bindings.toMap()

    override suspend fun categoryFor(role: CategoryRole): Long? = bindings[role]

    override suspend fun bindIfMissing(role: CategoryRole, categoryId: Long): Boolean {
        if (role in bindings) return false
        bindings = bindings + (role to categoryId)
        return true
    }
}
