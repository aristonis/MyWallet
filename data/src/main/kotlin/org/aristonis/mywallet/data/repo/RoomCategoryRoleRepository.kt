package org.aristonis.mywallet.data.repo

import org.aristonis.mywallet.data.db.CategoryRoleDao
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.port.CategoryRoleRepository

/** Resolves valid role categories and preserves existing bindings when a category is created. */
class RoomCategoryRoleRepository(private val dao: CategoryRoleDao) : CategoryRoleRepository {
    override suspend fun categoryFor(role: CategoryRole): Long? = dao.categoryFor(role.name, role.kind.name)

    override suspend fun bindIfMissing(role: CategoryRole, categoryId: Long): Boolean =
        dao.bindIfMissing(role.name, role.kind.name, categoryId)
}
