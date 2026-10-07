package org.aristonis.mywallet.ui.category

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.ui.components.MenuAction
import org.aristonis.mywallet.ui.components.OverflowMenu
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.label
import org.aristonis.mywallet.ui.text
import org.aristonis.mywallet.ui.message.text

@Composable
private fun ManageCategoryRow.labelText(): String = category.label().text()

/** What the screen is currently asking the user to confirm or type. */
private sealed interface CategoryPrompt {
    data class Add(val kind: CategoryKind, val parentId: Long?, val parentName: String?) : CategoryPrompt
    data class Rename(val id: Long, val current: String) : CategoryPrompt
    data class Delete(
        val id: Long,
        val name: String,
        val affected: Int,
        val childCount: Int,
        /** A sub-category delete only clears the finer label; the spend stays under its parent. */
        val isSubCategory: Boolean = false,
    ) : CategoryPrompt
}

/**
 * Keeps an open prompt across recreation. Without it a rotation mid-confirm drops the dialog, and
 * the delete confirmation is the one place the user is told what they are about to move.
 */
private val CategoryPromptSaver = listSaver<CategoryPrompt?, Any?>(
    save = { prompt ->
        when (prompt) {
            null -> emptyList()
            is CategoryPrompt.Add -> listOf("add", prompt.kind.name, prompt.parentId, prompt.parentName)
            is CategoryPrompt.Rename -> listOf("rename", prompt.id, prompt.current)
            is CategoryPrompt.Delete ->
                listOf("delete", prompt.id, prompt.name, prompt.affected, prompt.childCount, prompt.isSubCategory)
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            "add" -> CategoryPrompt.Add(
                kind = CategoryKind.valueOf(saved[1] as String),
                parentId = saved[2] as Long?,
                parentName = saved[3] as String?,
            )
            "rename" -> CategoryPrompt.Rename(id = saved[1] as Long, current = saved[2] as String)
            "delete" -> CategoryPrompt.Delete(
                id = saved[1] as Long,
                name = saved[2] as String,
                affected = saved[3] as Int,
                childCount = saved[4] as Int,
                isSubCategory = saved[5] as Boolean,
            )
            else -> null
        }
    },
)

/**
 * Manage Categories: the two-level tree for income and expense, with add, rename and delete.
 *
 * Deleting never loses a transaction, but the two cases end differently and the confirmation says
 * which one applies. Deleting a parent moves its spend to that kind's Uncategorized bucket, counting
 * the transactions filed under its sub-categories too. Deleting a sub-category only clears the finer
 * label, so the spend stays where it was. The bucket itself cannot be renamed or deleted, since it is
 * what every other delete falls back to. [onDone] returns to the caller. No nav library.
 */
@Composable
fun ManageCategoriesScreen(
    onDone: () -> Unit,
    viewModel: ManageCategoriesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)
    ManageCategoriesContent(
        state = state,
        onCreate = viewModel::create,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onAcknowledge = viewModel::acknowledge,
        affectedByChild = viewModel::affectedBy,
        onDone = onDone,
    )
}

@Composable
internal fun ManageCategoriesContent(
    state: ManageCategoriesUiState,
    onCreate: (String, CategoryKind, Long?) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onAcknowledge: () -> Unit,
    affectedByChild: (Long) -> Int,
    onDone: () -> Unit,
) {
    // Saved across recreation so a rotation mid-edit does not silently drop what was being typed.
    var prompt by rememberSaveable(stateSaver = CategoryPromptSaver) { mutableStateOf<CategoryPrompt?>(null) }

    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.categories_title), onBack = onDone)
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { message ->
                Text(
                    message.text(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.lastDelete?.let { outcome ->
                Text(deleteOutcomeText(outcome), style = MaterialTheme.typography.bodyMedium)
            }

            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                categorySection(
                    heading = R.string.categories_expense,
                    emptyMessage = R.string.categories_expense_empty,
                    kind = CategoryKind.EXPENSE,
                    rows = state.expense,
                    affectedByChild = affectedByChild,
                ) { prompt = it }
                categorySection(
                    heading = R.string.categories_income,
                    emptyMessage = R.string.categories_income_empty,
                    kind = CategoryKind.INCOME,
                    rows = state.income,
                    affectedByChild = affectedByChild,
                ) { prompt = it }
            }
        }
    }

    when (val current = prompt) {
        null -> Unit
        is CategoryPrompt.Add -> NamePromptDialog(
            title = current.parentName?.let { stringResource(R.string.category_new_sub, it) }
                ?: stringResource(R.string.category_new),
            initial = "",
            confirmLabel = stringResource(R.string.action_add),
            onConfirm = { name ->
                onAcknowledge()
                onCreate(name, current.kind, current.parentId)
                prompt = null
            },
            onDismiss = { prompt = null },
        )
        is CategoryPrompt.Rename -> NamePromptDialog(
            title = stringResource(R.string.category_rename_title),
            initial = current.current,
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { name ->
                onAcknowledge()
                onRename(current.id, name)
                prompt = null
            },
            onDismiss = { prompt = null },
        )
        is CategoryPrompt.Delete -> AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text(stringResource(R.string.delete_category_title, current.name)) },
            text = { Text(deleteExplanation(current)) },
            confirmButton = {
                TextButton(onClick = {
                    onAcknowledge()
                    onDelete(current.id)
                    prompt = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { prompt = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * Says exactly what the delete will do. The counts are the point: a user told "1 transaction" who
 * then loses three has been misled at the one moment they were asked to decide.
 */
/** What actually happened, worded for the case that happened. */
@Composable
private fun deleteOutcomeText(outcome: DeleteOutcome): String = when {
    outcome.affected == 0 -> stringResource(R.string.category_deleted_none)
    outcome.wasSubCategory ->
        pluralStringResource(R.plurals.category_deleted_sub, outcome.affected, outcome.affected)
    else -> pluralStringResource(R.plurals.category_deleted_parent, outcome.affected, outcome.affected)
}

@Composable
private fun deleteExplanation(prompt: CategoryPrompt.Delete): String {
    // A sub-category delete and a parent delete do different things to the same transactions, so
    // they cannot share one sentence: the first clears the finer label and leaves the spend where it
    // is, the second moves it somewhere else entirely.
    if (prompt.isSubCategory) {
        return if (prompt.affected == 0) {
            stringResource(R.string.delete_category_sub_none)
        } else {
            pluralStringResource(R.plurals.delete_category_sub_affected, prompt.affected, prompt.affected)
        }
    }
    val subCategories = if (prompt.childCount == 0) {
        ""
    } else {
        pluralStringResource(R.plurals.delete_category_children, prompt.childCount, prompt.childCount)
    }
    val transactions = if (prompt.affected == 0) {
        stringResource(R.string.delete_category_none)
    } else {
        pluralStringResource(R.plurals.delete_category_affected, prompt.affected, prompt.affected)
    }
    return subCategories + transactions
}

private fun androidx.compose.foundation.lazy.LazyListScope.categorySection(
    @androidx.annotation.StringRes heading: Int,
    @androidx.annotation.StringRes emptyMessage: Int,
    kind: CategoryKind,
    rows: List<ManageCategoryRow>,
    affectedByChild: (Long) -> Int,
    onPrompt: (CategoryPrompt) -> Unit,
) {
    item(key = "heading-$kind") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(heading), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onPrompt(CategoryPrompt.Add(kind, null, null)) }) {
                Text(stringResource(R.string.action_add))
            }
        }
    }
    if (rows.isEmpty()) {
        item(key = "empty-$kind") {
            Text(stringResource(emptyMessage), style = MaterialTheme.typography.bodyMedium)
        }
    }
    items(rows, key = { "row-${it.category.id}" }) { row ->
        CategoryCard(row = row, affectedByChild = affectedByChild, onPrompt = onPrompt)
    }
}

@Composable
private fun CategoryCard(
    row: ManageCategoryRow,
    affectedByChild: (Long) -> Int,
    onPrompt: (CategoryPrompt) -> Unit,
) {
    // Resolved once, in composition: the click handlers below need the same text and run outside it.
    val rowLabel = row.labelText()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(rowLabel, style = MaterialTheme.typography.bodyLarge)
                    if (row.affectedTransactions > 0) {
                        Text(
                            pluralStringResource(
                                R.plurals.category_transaction_count,
                                row.affectedTransactions,
                                row.affectedTransactions,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OverflowMenu(
                    contentDescription = stringResource(R.string.cd_row_actions, rowLabel),
                    actions = categoryActions(row, rowLabel, onPrompt),
                )
            }
            row.children.forEach { child ->
                val childLabel = child.label().text()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = childLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OverflowMenu(
                        contentDescription = stringResource(R.string.cd_row_actions, childLabel),
                        actions = subCategoryActions(child, childLabel, affectedByChild, onPrompt),
                    )
                }
            }
        }
    }
}

/**
 * A parent category can gain a sub-category, and — unless it is the app-owned bucket — be renamed or
 * deleted. The bucket is what every other delete falls back to, and its label is resolved rather than
 * stored, so neither action would mean anything for it.
 */
@Composable
private fun categoryActions(
    row: ManageCategoryRow,
    rowLabel: String,
    onPrompt: (CategoryPrompt) -> Unit,
): List<MenuAction> = buildList {
    add(
        MenuAction(
            label = stringResource(R.string.category_add_sub),
            onClick = { onPrompt(CategoryPrompt.Add(row.category.kind, row.category.id, rowLabel)) },
            icon = WalletIcons.Add,
        ),
    )
    if (!row.isProtected) {
        add(
            MenuAction(
                label = stringResource(R.string.action_rename),
                onClick = { onPrompt(CategoryPrompt.Rename(row.category.id, row.category.name)) },
                icon = WalletIcons.Edit,
            ),
        )
        add(
            MenuAction(
                label = stringResource(R.string.action_delete),
                onClick = {
                    onPrompt(
                        CategoryPrompt.Delete(
                            id = row.category.id,
                            name = rowLabel,
                            affected = row.affectedTransactions,
                            childCount = row.children.size,
                        ),
                    )
                },
                icon = WalletIcons.Delete,
                isDestructive = true,
            ),
        )
    }
}

/** A sub-category goes no deeper, so it can only be renamed or removed. */
@Composable
private fun subCategoryActions(
    child: Category,
    childLabel: String,
    affectedByChild: (Long) -> Int,
    onPrompt: (CategoryPrompt) -> Unit,
): List<MenuAction> = listOf(
    MenuAction(
        label = stringResource(R.string.action_rename),
        onClick = { onPrompt(CategoryPrompt.Rename(child.id, child.name)) },
        icon = WalletIcons.Edit,
    ),
    MenuAction(
        label = stringResource(R.string.action_delete),
        onClick = {
            onPrompt(
                CategoryPrompt.Delete(
                    id = child.id,
                    name = childLabel,
                    affected = affectedByChild(child.id),
                    childCount = 0,
                    isSubCategory = true,
                ),
            )
        },
        icon = WalletIcons.Delete,
        isDestructive = true,
    ),
)

@Composable
private fun NamePromptDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.field_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun ManageCategoriesPreview() {
    val food = Category(id = 1, name = "Food", kind = CategoryKind.EXPENSE)
    MyWalletTheme {
        ManageCategoriesContent(
            state = ManageCategoriesUiState(
                expense = listOf(
                    ManageCategoryRow(
                        category = food,
                        children = listOf(Category(id = 2, name = "Groceries", kind = CategoryKind.EXPENSE, parentId = 1)),
                        affectedTransactions = 12,
                    ),
                ),
                income = listOf(ManageCategoryRow(category = Category(id = 3, name = "Salary", kind = CategoryKind.INCOME))),
                isLoading = false,
            ),
            onCreate = { _, _, _ -> },
            onRename = { _, _ -> },
            onDelete = {},
            onAcknowledge = {},
            affectedByChild = { 0 },
            onDone = {},
        )
    }
}
