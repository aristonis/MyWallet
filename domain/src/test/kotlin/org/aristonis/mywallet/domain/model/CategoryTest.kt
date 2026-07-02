package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryTest {

    @Test
    fun topLevelHasNoParent() {
        val c = Category(name = "Food", kind = CategoryKind.EXPENSE)
        assertFalse(c.isSubCategory)
    }

    @Test
    fun subCategoryHasParent() {
        val c = Category(name = "Groceries", kind = CategoryKind.EXPENSE, parentId = 1)
        assertTrue(c.isSubCategory)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankName_throws() {
        Category(name = "", kind = CategoryKind.INCOME)
    }
}
