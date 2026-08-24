package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import java.util.Locale

/**
 * The sibling-name rule shared by [CreateCategory] and [RenameCategory]: within one [kind] and one
 * [parentId], a name may be used once, compared without regard to case.
 *
 * Folding goes through [Locale.ROOT] on purpose. The default locale would be used otherwise, and on
 * a Turkish device "I" folds to "ı" rather than "i" — so "Internet" and "internet" would look like
 * two different names here while the database index, which folds the ASCII alphabet only, still
 * refuses the second one with a raw constraint error instead of the typed error the UI can show.
 *
 * [excludingId] lets a rename skip the row it is renaming, so re-saving a name unchanged, or
 * changing only its casing, is not reported as a clash with itself.
 */
internal fun List<Category>.requireNameFreeAmongSiblings(
    name: String,
    kind: CategoryKind,
    parentId: Long?,
    excludingId: Long? = null,
) {
    val folded = name.lowercase(Locale.ROOT)
    val taken = any { sibling ->
        sibling.id != excludingId &&
            sibling.kind == kind &&
            sibling.parentId == parentId &&
            sibling.name.lowercase(Locale.ROOT) == folded
    }
    if (taken) throw WalletException.DuplicateCategoryName(name)
}

/**
 * Refuses a name that would collide with an app-owned bucket's stored placeholder.
 *
 * The bucket is identified by its system key, but the key is also what the row is stored under, and
 * that name shares one uniqueness namespace with everything the user creates. A user who managed to
 * take the name first would make the bucket impossible to create, and every later delete of that
 * kind would fail with an error naming a category they think is unrelated.
 */
internal fun requireNameNotReserved(name: String) {
    val folded = name.lowercase(Locale.ROOT)
    val isReserved = CategoryKind.entries.any {
        Category.uncategorizedKeyFor(it).lowercase(Locale.ROOT) == folded
    }
    if (isReserved) throw WalletException.DuplicateCategoryName(name)
}
