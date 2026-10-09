package org.aristonis.mywallet.data.backup

import org.aristonis.mywallet.data.db.StoredEntry
import org.aristonis.mywallet.data.db.TOP_LEVEL_PARENT_ID
import org.aristonis.mywallet.data.db.toDomain
import org.aristonis.mywallet.data.db.toTransaction
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.TrackingRule
import org.aristonis.mywallet.domain.ledger.recipe
import org.aristonis.mywallet.domain.model.DebtSide

/**
 * Refuses, as [WalletException.BackupInvalid], a format-2 backup that could not be restored and then
 * read back. Backups are plain JSON in an open-source app and get edited by hand, and the JSON decode
 * accepts any string for a kind, a theme or an amount, so this is where such a file is stopped:
 * before a single row of the wallet is replaced.
 *
 * Every rule here is something the database or the read side would otherwise trip over later,
 * with an error the owner could do nothing about.
 */
internal fun requireRestorable(backup: WalletBackupV2) {
    try {
        checkRestorable(backup)
    } catch (e: IllegalArgumentException) {
        // A failed check below, an unknown enum name, or a number that does not parse.
        throw WalletException.BackupInvalid(e)
    } catch (e: WalletException.UnreadableRow) {
        throw WalletException.BackupInvalid(e)
    }
}

private fun checkRestorable(backup: WalletBackupV2) {
    // A real wallet backup is always taken after onboarding, so it carries settings (the base
    // currency lives there). Without them the restored wallet bounces straight back to onboarding.
    val settings = requireNotNull(backup.settings) { "the file has no settings" }
    // Read every reference row the way the app will. A bad theme would stop the app at launch,
    // before Settings could be reached to restore anything else.
    settings.toEntity().toDomain()
    backup.accounts.forEach { it.toEntity().toDomain() }
    backup.categories.forEach { it.toEntity().toDomain() }
    backup.currencies.forEach { it.toEntity().toDomain() }
    backup.rates.forEach { it.toEntity().toDomain() }

    requireSoundCategoryTree(backup.categories)
    requireKeysResolve(backup)
    requireUniqueKeys(backup)
    requireReadableEntries(backup)
}

/**
 * Nothing in the database enforces the category tree: `parentId` carries no foreign key, so a
 * broken link survives the restore and only shows itself later, when deleting such a category
 * re-files its legs onto an id that does not exist. A self-parent, a parent that is itself a child,
 * a parent of the other kind, or an id of zero all lead there.
 */
private fun requireSoundCategoryTree(categories: List<CategoryDto>) {
    val byId = categories.associateBy { it.id }
    categories.forEach { category ->
        require(category.id != 0L) { "category ${category.name} has id 0" }
        val parentId = category.parentId ?: return@forEach
        val parent = byId[parentId]
        require(parentId != category.id && parent != null && parent.parentId == null && parent.kind == category.kind) {
            "category ${category.id} hangs under $parentId, which is not a top-level category of its kind"
        }
    }
}

/**
 * Every id a row names must be in the file. Some of these links have a foreign key, and a missing
 * parent would abort the restore part-way with a raw SQLite error; a deduction item's category has
 * none, and would leave the item naming something that is not there.
 *
 * The one link allowed to point nowhere is a leg's main category. The old transactions table had no
 * key on it, so a wallet can hold an entry on a category deleted long ago; the upgrade keeps such an
 * entry as it is, and a backup of that wallet must restore. Its sub-category does have a key, so a
 * missing one is still refused.
 */
private fun requireKeysResolve(backup: WalletBackupV2) {
    val categoryIds = backup.categories.mapTo(HashSet()) { it.id }
    val entryIds = uniqueIds(backup.entries.map { it.id }, "entry")
    val debtIds = uniqueIds(backup.debts.map { it.id }, "debt")
    val itemIds = uniqueIds(backup.deductionItems.map { it.id }, "deduction item")
    uniqueIds(backup.legacyRows.map { it.id }, "raw copy")
    uniqueIds(backup.categoryRoles.map { it.role }, "category role")

    backup.legs.forEach { leg ->
        require(leg.entryId in entryIds) { "a leg belongs to entry ${leg.entryId}, which is not in the file" }
        requireIn(leg.subCategoryId, categoryIds, "category")
        requireIn(leg.debtId, debtIds, "debt")
    }
    backup.debts.forEach { debt ->
        require(DebtSide.entries.any { it.name == debt.side }) { "debt ${debt.id} has an unknown side ${debt.side}" }
    }
    backup.deductionItems.forEach { requireIn(it.expenseCategoryId, categoryIds, "category") }
    backup.deductionAutoCategories.forEach {
        requireIn(it.itemId, itemIds, "deduction item")
        requireIn(it.incomeCategoryId, categoryIds, "category")
    }
    backup.deductionLines.forEach {
        requireIn(it.entryId, entryIds, "entry")
        requireIn(it.itemId, itemIds, "deduction item")
    }
    backup.categoryRoles.forEach { requireIn(it.categoryId, categoryIds, "category") }
}

/**
 * Every entry must read back through its kind's recipe, and each leg on a category must be of a role
 * that counts in Tracking. The category's own kind is not checked: 1.1.1 never checked it, so a
 * wallet can hold an income filed under an expense category, and the upgrade keeps it with the same
 * numbers. A new save still refuses that; a restore puts back what was there. The one exception is an old row the upgrade could not read: it restores
 * as it was, with no legs, but only together with its raw copy, so export, clear data and restore
 * never lose it and nothing is ever summed from it. A raw copy may also stand alone, or no longer
 * match its entry: the owner may have deleted or edited that entry since the upgrade.
 */
private fun requireReadableEntries(backup: WalletBackupV2) {
    val legsByEntry = backup.legs.groupBy { it.entryId }
    val rawCopyById = backup.legacyRows.associateBy { it.id }
    val debtSides = backup.debts.associate { debt -> debt.id to DebtSide.entries.first { it.name == debt.side } }

    backup.entries.forEach { entry ->
        val legs = legsByEntry[entry.id].orEmpty()
        if (entry.kind == BackupUpgrade.KIND_UNREADABLE) {
            val rawCopy = requireNotNull(rawCopyById[entry.id]) { "unreadable entry ${entry.id} has no raw copy" }
            require(rawCopy.reason in BackupUpgrade.UNREADABLE_REASONS) {
                "unreadable entry ${entry.id} has a raw copy with reason ${rawCopy.reason}, which keeps a row readable"
            }
            require(legs.isEmpty()) { "unreadable entry ${entry.id} has legs" }
        } else {
            requireReadable(entry, legs, debtSides)
        }
    }
}

/** Throws [WalletException.UnreadableRow] when the recipe cannot read the entry back. */
private fun requireReadable(
    entry: EntryDto,
    legs: List<LegDto>,
    debtSides: Map<Long, DebtSide>,
) {
    val transaction = StoredEntry(entry.toEntity(), legs.map { it.toEntity() }).toTransaction(debtSides)
    val rules = transaction.recipe().trackingRules(transaction)
    legs.forEach { leg ->
        val categoryId = leg.categoryId ?: return@forEach
        // The recipe has just read every role, so the name is one this build knows.
        val role = LegRole.entries.first { it.name == leg.role }
        val rule = rules[role]
        require(rule != null && rule != TrackingRule.NOT_COUNTED) {
            "the ${leg.role} leg of entry ${entry.id} is on category $categoryId, but that role never counts"
        }
    }
}

/**
 * Rows the database's unique indexes would refuse. Left to the database they abort the restore with a
 * raw SQLite error; caught here they are a file that cannot be restored, said as such.
 *
 * Category names compare the way the `name` column's NOCASE collation does, which folds only A-Z:
 * "Food" and "FOOD" clash, "Éclair" and "éclair" do not.
 */
private fun requireUniqueKeys(backup: WalletBackupV2) {
    requireUnique(backup.legs.map { Triple(it.entryId, it.role, it.position) }, "legs of one entry share a role and position")
    requireUnique(
        backup.categories.map { Triple(it.kind, it.parentId ?: TOP_LEVEL_PARENT_ID, sqliteNoCaseKey(it.name)) },
        "two sibling categories of one kind share a name",
    )
    requireUnique(backup.categories.mapNotNull { it.systemKey }, "two categories share a system key")
    requireUnique(backup.deductionLines.map { it.entryId to it.position }, "deduction lines of one entry share a position")
    requireUnique(backup.deductionLines.map { it.id }, "two deduction lines share an id")
    requireUnique(backup.deductionAutoCategories.map { it.itemId to it.incomeCategoryId }, "an auto-add link is listed twice")
    requireUnique(backup.deductionItems.map { it.nameKey }, "two deduction items share a name key")
    requireUnique(backup.debts.map { Triple(it.side, it.currencyCode, it.counterpartyKey) }, "two debts share a name, side and currency")
}

private fun <T> requireUnique(keys: List<T>, problem: String) {
    require(keys.size == keys.toSet().size) { problem }
}

/** SQLite's NOCASE folds ASCII A-Z only; Kotlin's case folding goes much further, so it is not used. */
private fun sqliteNoCaseKey(name: String): String =
    String(CharArray(name.length) { i -> name[i].let { if (it in 'A'..'Z') it + ('a' - 'A') else it } })

private fun <T> uniqueIds(ids: List<T>, what: String): Set<T> {
    val unique = ids.toSet()
    require(unique.size == ids.size) { "two ${what}s share an id" }
    return unique
}

private fun requireIn(id: Long?, ids: Set<Long>, what: String) {
    if (id == null) return
    require(id in ids) { "a row names $what $id, which is not in the file" }
}
