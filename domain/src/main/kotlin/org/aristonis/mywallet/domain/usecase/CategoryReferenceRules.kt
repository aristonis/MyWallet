package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.port.CategoryRepository

/**
 * The category rule shared by [RecordIncome], [RecordExpense] and [UpdateTransaction]: a
 * transaction's main category is a top-level one, and its optional sub-category is a child of that
 * exact category, of the same kind.
 *
 * The top-level half is the one that keeps data intact. Deleting a sub-category clears only the
 * finer `subCategoryId` column — the spend is meant to stay in its parent — and `categoryId`
 * carries no foreign key, because an income or expense must always name a category. Let a
 * sub-category into `categoryId` and the two facts combine: the delete matches nothing, the row is
 * removed anyway, and the transaction is left naming a category that no longer exists. Nothing
 * fails, nothing is logged, and the money disappears from every report.
 *
 * The sub-category half is the same rule the picker applies, restated where every caller passes
 * through: a child of some other parent would file the spend under a label no breakdown could
 * resolve against the category it claims to sit in.
 */
internal suspend fun CategoryRepository.requireTransactionCategories(categoryId: Long, subCategoryId: Long?) {
    val category = findById(categoryId) ?: throw WalletException.CategoryNotFound(categoryId)
    if (category.isSubCategory) throw WalletException.CategoryNotTopLevel(categoryId)

    if (subCategoryId == null) return
    val subCategory = findById(subCategoryId) ?: throw WalletException.CategoryNotFound(subCategoryId)
    if (subCategory.parentId != categoryId || subCategory.kind != category.kind) {
        throw WalletException.CategoryKindMismatch(categoryId)
    }
}
