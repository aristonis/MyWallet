package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The seed and the upgrade keep their own copies of the role strings, frozen at release. These
 * tests fail the build if either copy drifts from the [CategoryRole] enum the app reads them as.
 */
class CategoryRoleContractTest {

    private fun roleNamed(name: String): CategoryRole {
        val role = CategoryRole.entries.firstOrNull { it.name == name }
        assertNotNull("\"$name\" is not a CategoryRole", role)
        return role!!
    }

    @Test
    fun everySeededRoleIsACategoryRoleWithTheSameLiteralNameAndKind() {
        DefaultData.preMadeCategories.mapNotNull { pm -> pm.role?.let { pm to roleNamed(it) } }.forEach { (pm, role) ->
            assertEquals("${role.name} name", role.literalName, pm.name)
            assertEquals("${role.name} kind", role.kind, pm.kind)
        }
    }

    @Test
    fun everyUpgradeRoleIsACategoryRoleWithTheSameLiteralNameAndKind() {
        PRE_MADE_ROLES.forEach { (roleName, kind, name) ->
            val role = roleNamed(roleName)
            assertEquals("$roleName name", role.literalName, name)
            assertEquals("$roleName kind", role.kind, CategoryKind.valueOf(kind))
        }
    }

    /** A role as the seed, an upgrade and the enum each spell it: stored name, category name, kind. */
    private data class RoleSpec(val role: String, val name: String, val kind: CategoryKind)

    /** The five roles as released. Never edit these rows: stored data depends on every one. */
    private val frozen = setOf(
        RoleSpec("INTEREST_IN", "Interest", CategoryKind.INCOME),
        RoleSpec("INTEREST_OUT", "Interest", CategoryKind.EXPENSE),
        RoleSpec("FEES", "Fees", CategoryKind.EXPENSE),
        RoleSpec("LOST_DEBT", "Lost debt", CategoryKind.EXPENSE),
        RoleSpec("DEBT_FORGIVEN", "Debt forgiven", CategoryKind.INCOME),
    )

    /** One entry per migration that writes roles; a later migration adds its list here, never edits another's. */
    private val migrationRoleLists: List<List<RoleSpec>> = listOf(
        PRE_MADE_ROLES.map { (role, kind, name) -> RoleSpec(role, name, CategoryKind.valueOf(kind)) },
    )

    @Test
    fun theFiveFrozenRolesAreSpelledOutLiterally() {
        assertEquals(frozen, CategoryRole.entries.map { RoleSpec(it.name, it.literalName, it.kind) }.toSet())
    }

    @Test
    fun enumSeedAndTheUnionOfMigrationListsAgree() {
        val seed = DefaultData.preMadeCategories
            .mapNotNull { pm -> pm.role?.let { RoleSpec(it, pm.name, pm.kind) } }.toSet()
        val migrated = migrationRoleLists.flatten().toSet()
        val fromEnum = CategoryRole.entries.map { RoleSpec(it.name, it.literalName, it.kind) }.toSet()

        assertEquals(frozen, seed)
        assertEquals(frozen, migrated)
        assertEquals(fromEnum, seed)
    }
}
