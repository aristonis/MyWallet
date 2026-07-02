package org.aristonis.mywallet.domain.model

/** Income vs expense classification. Transfers are never categorized. */
enum class CategoryKind { INCOME, EXPENSE }

/**
 * A category (or, when [parentId] is set, a sub-category — max two levels). A sub-category is
 * expected to share its parent's [kind]; that cross-entity check lives in the category use-cases.
 */
data class Category(
    val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    val parentId: Long? = null,
) {
    init { require(name.isNotBlank()) { "category name must not be blank" } }

    val isSubCategory: Boolean get() = parentId != null
}
