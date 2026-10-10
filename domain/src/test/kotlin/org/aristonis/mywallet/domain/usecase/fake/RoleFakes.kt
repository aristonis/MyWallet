package org.aristonis.mywallet.domain.usecase.fake

import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.port.CategoryRoleRepository

/** Role bindings held in memory. A role absent from [bindings] is one whose category was deleted. */
class FakeCategoryRoleRepository(initial: Map<CategoryRole, Long> = emptyMap()) : CategoryRoleRepository {
    private val bindings = initial.toMutableMap()

    /** The bindings as they stand, for asserting that nothing was re-bound. */
    val stored: Map<CategoryRole, Long> get() = bindings.toMap()

    override suspend fun categoryFor(role: CategoryRole): Long? = bindings[role]
}
