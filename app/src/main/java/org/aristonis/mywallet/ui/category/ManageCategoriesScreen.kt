package org.aristonis.mywallet.ui.category

// UI copy hardcoded; localizing strings (RTL/i18n) comes later.

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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.label

private fun ManageCategoryRow.label(): String = category.label()

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
private fun ManageCategoriesContent(
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

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Categories", style = MaterialTheme.typography.headlineSmall)

            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            state.lastDelete?.let { outcome ->
                Text(deleteOutcomeText(outcome), style = MaterialTheme.typography.bodyMedium)
            }

            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                categorySection("Expense", CategoryKind.EXPENSE, state.expense, affectedByChild) { prompt = it }
                categorySection("Income", CategoryKind.INCOME, state.income, affectedByChild) { prompt = it }
            }

            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }

    when (val current = prompt) {
        null -> Unit
        is CategoryPrompt.Add -> NamePromptDialog(
            title = current.parentName?.let { "New sub-category under $it" } ?: "New category",
            initial = "",
            confirmLabel = "Add",
            onConfirm = { name ->
                onAcknowledge()
                onCreate(name, current.kind, current.parentId)
                prompt = null
            },
            onDismiss = { prompt = null },
        )
        is CategoryPrompt.Rename -> NamePromptDialog(
            title = "Rename category",
            initial = current.current,
            confirmLabel = "Save",
            onConfirm = { name ->
                onAcknowledge()
                onRename(current.id, name)
                prompt = null
            },
            onDismiss = { prompt = null },
        )
        is CategoryPrompt.Delete -> AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text("Delete ${current.name}?") },
            text = { Text(deleteExplanation(current)) },
            confirmButton = {
                TextButton(onClick = {
                    onAcknowledge()
                    onDelete(current.id)
                    prompt = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { prompt = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Says exactly what the delete will do. The counts are the point: a user told "1 transaction" who
 * then loses three has been misled at the one moment they were asked to decide.
 */
/** What actually happened, worded for the case that happened. */
private fun deleteOutcomeText(outcome: DeleteOutcome): String = when {
    outcome.affected == 0 -> "Deleted. No transactions were affected."
    outcome.wasSubCategory && outcome.affected == 1 ->
        "Deleted. 1 transaction kept its category and lost only the finer label."
    outcome.wasSubCategory ->
        "Deleted. ${outcome.affected} transactions kept their category and lost only the finer label."
    outcome.affected == 1 -> "Deleted. 1 transaction moved to Uncategorized."
    else -> "Deleted. ${outcome.affected} transactions moved to Uncategorized."
}

private fun deleteExplanation(prompt: CategoryPrompt.Delete): String {
    // A sub-category delete and a parent delete do different things to the same transactions, so
    // they cannot share one sentence: the first clears the finer label and leaves the spend where it
    // is, the second moves it somewhere else entirely.
    if (prompt.isSubCategory) {
        return when (prompt.affected) {
            0 -> "No transactions use it, so nothing changes."
            1 -> "1 transaction keeps its category and loses only this finer label."
            else -> "${prompt.affected} transactions keep their category and lose only this finer label."
        }
    }
    val subCategories = when (prompt.childCount) {
        0 -> ""
        1 -> "Its 1 sub-category goes too. "
        else -> "Its ${prompt.childCount} sub-categories go too. "
    }
    val transactions = when (prompt.affected) {
        0 -> "No transactions use it, so nothing moves."
        1 -> "1 transaction moves to Uncategorized — nothing is lost."
        else -> "${prompt.affected} transactions move to Uncategorized — nothing is lost."
    }
    return subCategories + transactions
}

private fun androidx.compose.foundation.lazy.LazyListScope.categorySection(
    heading: String,
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
            Text(heading, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onPrompt(CategoryPrompt.Add(kind, null, null)) }) { Text("Add") }
        }
    }
    if (rows.isEmpty()) {
        item(key = "empty-$kind") {
            Text("No $heading categories yet.", style = MaterialTheme.typography.bodyMedium)
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(row.label(), style = MaterialTheme.typography.bodyLarge)
                    if (row.affectedTransactions > 0) {
                        Text(
                            "${row.affectedTransactions} transactions",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row {
                    TextButton(onClick = {
                        onPrompt(CategoryPrompt.Add(row.category.kind, row.category.id, row.label()))
                    }) { Text("Add sub") }
                    // The bucket is what every other delete falls back to, and its visible label is
                    // resolved rather than stored, so neither action would mean anything here.
                    if (!row.isProtected) {
                        TextButton(onClick = {
                            onPrompt(CategoryPrompt.Rename(row.category.id, row.category.name))
                        }) { Text("Rename") }
                        TextButton(onClick = {
                            onPrompt(
                                CategoryPrompt.Delete(
                                    id = row.category.id,
                                    name = row.label(),
                                    affected = row.affectedTransactions,
                                    childCount = row.children.size,
                                ),
                            )
                        }) { Text("Delete") }
                    }
                }
            }
            row.children.forEach { child ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(child.label(), style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { onPrompt(CategoryPrompt.Rename(child.id, child.name)) }) {
                            Text("Rename")
                        }
                        TextButton(onClick = {
                            onPrompt(
                                CategoryPrompt.Delete(
                                    id = child.id,
                                    name = child.label(),
                                    affected = affectedByChild(child.id),
                                    childCount = 0,
                                    isSubCategory = true,
                                ),
                            )
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

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
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
