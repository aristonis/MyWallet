package org.aristonis.mywallet.domain.model

/** Income vs expense classification. Transfers are never categorized. */
enum class CategoryKind { INCOME, EXPENSE }

/**
 * A category (or, when [parentId] is set, a sub-category — max two levels). A sub-category is
 * expected to share its parent's [kind]; that cross-entity check lives in the category use-cases.
 *
 * [systemKey] marks the two app-owned fallback buckets that deleted categories' transactions move
 * to. It is `null` for everything the user creates, and the key — never the display name — is the
 * identity, so a bucket cannot be orphaned by anything that happens to its text.
 *
 * For such a row [name] is a stored PLACEHOLDER and is never shown: the layer that writes it has no
 * resources and no locale, so it cannot produce a label anyone would want to read. The displayed
 * text comes from [displayName], which resolves it from [systemKey] instead. That is also why
 * renaming one is refused — there is nothing the user could usefully rename.
 */
data class Category(
    val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    val parentId: Long? = null,
    val systemKey: String? = null,
) {
    init { require(name.isNotBlank()) { "category name must not be blank" } }

    val isSubCategory: Boolean get() = parentId != null

    /** App-owned rows are protected: they can be neither renamed nor deleted. */
    val isSystem: Boolean get() = systemKey != null

    /**
     * What to show for this category. A user category is its own [name]; an app-owned bucket goes
     * through [resolveSystemLabel], which is where the UI hands back a localized string for the
     * given [systemKey]. Keeping the seam here means only one place ever decides which of the two
     * applies, so no screen can end up printing a bucket's raw placeholder.
     */
    fun displayName(resolveSystemLabel: (String) -> String): String =
        systemKey?.let(resolveSystemLabel) ?: name

    companion object {
        /** The reserved key of the fallback bucket that absorbs deleted [kind] categories. */
        fun uncategorizedKeyFor(kind: CategoryKind): String = when (kind) {
            CategoryKind.INCOME -> "UNCATEGORIZED_INCOME"
            CategoryKind.EXPENSE -> "UNCATEGORIZED_EXPENSE"
        }
    }
}
