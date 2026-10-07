package org.aristonis.mywallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.account.ManageAccountRow
import org.aristonis.mywallet.ui.account.ManageAccountsContent
import org.aristonis.mywallet.ui.account.ManageAccountsUiState
import org.aristonis.mywallet.ui.category.ManageCategoriesContent
import org.aristonis.mywallet.ui.category.ManageCategoriesUiState
import org.aristonis.mywallet.ui.category.ManageCategoryRow
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Three text buttons side by side fit at the default font size and stop fitting at 200%, where the
 * labels wrap into each other or run off the edge — and an action the user cannot reach is an action
 * the app no longer has. They live in a per-row menu instead, which always fits because the labels
 * get the width of a popup rather than a third of a row.
 *
 * What these pin is that moving them there kept every action, and that each one still does its job.
 */
@RunWith(AndroidJUnit4::class)
class RowActionsTest {

    @get:Rule val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { MyWalletTheme(dynamicColor = false) { content() } }
    }

    private fun account(name: String, archived: Boolean = false, hasTransactions: Boolean = false) =
        ManageAccountRow(
            account = Account(
                id = 1,
                name = name,
                typeKey = "cash",
                currencyCode = "USD",
                openingBalance = Money.zero("USD"),
                archived = archived,
            ),
            hasTransactions = hasTransactions,
        )

    private fun showAccounts(
        row: ManageAccountRow,
        onArchive: (Long) -> Unit = {},
        onUnarchive: (Long) -> Unit = {},
        onDelete: (Long) -> Unit = {},
        onEdit: (Long) -> Unit = {},
    ) = show {
        ManageAccountsContent(
            state = ManageAccountsUiState(rows = listOf(row), isLoading = false),
            onArchive = onArchive,
            onUnarchive = onUnarchive,
            onDelete = onDelete,
            onEdit = onEdit,
            onAddAccount = {},
            onDone = {},
        )
    }

    private fun openMenuFor(rowName: String) {
        compose.onNodeWithContentDescription("Actions for $rowName").performClick()
    }

    // --- accounts ---------------------------------------------------------------------------------

    @Test
    fun anActiveAccountOffersEditArchiveAndDelete() {
        showAccounts(account("Cash"))

        openMenuFor("Cash")

        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun anArchivedAccountOffersUnarchiveInsteadOfArchive() {
        showAccounts(account("Old Card", archived = true))

        openMenuFor("Old Card")

        compose.onNodeWithText("Unarchive").assertIsDisplayed()
        compose.onNodeWithText("Archive").assertDoesNotExist()
    }

    /** Deleting a used account would orphan its history, so the action is absent, not merely dimmed. */
    @Test
    fun anAccountWithHistoryCannotBeDeletedAndSaysWhy() {
        showAccounts(account("Cash", hasTransactions = true))

        compose.onNodeWithText("Has transactions — archive instead").assertIsDisplayed()
        openMenuFor("Cash")

        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun editingAnAccountFromTheMenuOpensThatAccount() {
        var edited: Long? = null
        showAccounts(account("Cash"), onEdit = { edited = it })

        openMenuFor("Cash")
        compose.onNodeWithText("Edit").performClick()

        assertEquals(1L, edited)
    }

    @Test
    fun archivingFromTheMenuArchivesThatAccount() {
        var archived: Long? = null
        showAccounts(account("Cash"), onArchive = { archived = it })

        openMenuFor("Cash")
        compose.onNodeWithText("Archive").performClick()

        assertEquals(1L, archived)
    }

    /** Delete is destructive, so the menu asks first rather than removing the account outright. */
    @Test
    fun deletingFromTheMenuConfirmsBeforeRemoving() {
        var deleted: Long? = null
        showAccounts(account("Cash"), onDelete = { deleted = it })

        openMenuFor("Cash")
        compose.onNodeWithText("Delete").performClick()

        compose.onNodeWithText("Delete Cash?").assertIsDisplayed()
        assertEquals(null, deleted)

        compose.onNodeWithText("Delete").performClick()
        assertEquals(1L, deleted)
    }

    // --- categories -------------------------------------------------------------------------------

    private fun showCategories(
        rows: List<ManageCategoryRow>,
        onRename: (Long, String) -> Unit = { _, _ -> },
        onCreate: (String, CategoryKind, Long?) -> Unit = { _, _, _ -> },
    ) = show {
        ManageCategoriesContent(
            state = ManageCategoriesUiState(expense = rows, isLoading = false),
            onCreate = onCreate,
            onRename = onRename,
            onDelete = {},
            onAcknowledge = {},
            affectedByChild = { 0 },
            onDone = {},
        )
    }

    private val food = Category(id = 1, name = "Food", kind = CategoryKind.EXPENSE)
    private val groceries = Category(id = 2, name = "Groceries", kind = CategoryKind.EXPENSE, parentId = 1)

    @Test
    fun aCategoryOffersAddSubRenameAndDelete() {
        showCategories(listOf(ManageCategoryRow(category = food)))

        openMenuFor("Food")

        compose.onNodeWithText("Add sub").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    /** A sub-category goes no deeper, so it is not offered one. */
    @Test
    fun aSubCategoryOffersOnlyRenameAndDelete() {
        showCategories(listOf(ManageCategoryRow(category = food, children = listOf(groceries))))

        openMenuFor("Groceries")

        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Add sub").assertDoesNotExist()
    }

    @Test
    fun renamingFromTheMenuOpensThePromptAndSaves() {
        var renamed: Pair<Long, String>? = null
        showCategories(listOf(ManageCategoryRow(category = food)), onRename = { id, name -> renamed = id to name })

        openMenuFor("Food")
        compose.onNodeWithText("Rename").performClick()

        compose.onNodeWithText("Rename category").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()

        assertEquals(1L to "Food", renamed)
    }
}
