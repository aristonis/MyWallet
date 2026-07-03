package org.aristonis.mywallet.ui.account

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.theme.MyWalletTheme

/**
 * Manage Accounts: archive/unarchive and delete existing accounts. Archived accounts are listed here
 * (greyed) so they can be brought back. Delete is only offered for accounts with no transactions;
 * used accounts show an "archive instead" hint. [onDone] returns to Home. No nav library.
 */
@Composable
fun ManageAccountsScreen(
    onDone: () -> Unit,
    onEditAccount: (Long) -> Unit,
    viewModel: ManageAccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)
    ManageAccountsContent(
        state = state,
        onArchive = viewModel::archive,
        onUnarchive = viewModel::unarchive,
        onDelete = viewModel::delete,
        onEdit = onEditAccount,
        onDone = onDone,
    )
}

@Composable
private fun ManageAccountsContent(
    state: ManageAccountsUiState,
    onArchive: (Long) -> Unit,
    onUnarchive: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDone: () -> Unit,
) {
    // Deleting an account is destructive, so it goes through a confirm dialog keyed on the pending id.
    var pendingDelete by remember { mutableStateOf<ManageAccountRow?>(null) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Accounts", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("Done") }
            }

            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            when {
                state.isLoading -> CircularProgressIndicator()

                state.rows.isEmpty() -> Text(
                    "No accounts yet. Add one from the home screen.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rows, key = { it.account.id }) { row ->
                        AccountManageCard(
                            row = row,
                            onEdit = { onEdit(row.account.id) },
                            onArchive = { onArchive(row.account.id) },
                            onUnarchive = { onUnarchive(row.account.id) },
                            onDeleteRequest = { pendingDelete = row },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${row.account.name}?") },
            text = { Text("This permanently removes the account. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(row.account.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AccountManageCard(
    row: ManageAccountRow,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.account.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (row.account.archived) {
                    Text("Archived", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "${typeName(row.account.typeKey)} · ${row.account.currencyCode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Every account can be edited (name/type always; currency only while it has no
                // transactions — the editor locks that field), so Edit is offered unconditionally.
                TextButton(onClick = onEdit) { Text("Edit") }
                if (row.account.archived) {
                    TextButton(onClick = onUnarchive) { Text("Unarchive") }
                } else {
                    TextButton(onClick = onArchive) { Text("Archive") }
                }
                // Deleting a used account would orphan its history, so Delete is only offered when the
                // account has no transactions; otherwise the user archives instead.
                if (row.hasTransactions) {
                    Text(
                        "Has transactions — archive instead",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                } else {
                    TextButton(onClick = onDeleteRequest) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun typeName(typeKey: String): String =
    AccountTypeRegistry.BuiltIns.all.firstOrNull { it.key == typeKey }?.displayName ?: typeKey

@Preview(showBackground = true)
@Composable
private fun ManageAccountsPreview() {
    MyWalletTheme(dynamicColor = false) {
        ManageAccountsContent(
            state = ManageAccountsUiState(
                isLoading = false,
                rows = listOf(
                    ManageAccountRow(
                        Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD")),
                        hasTransactions = true,
                    ),
                    ManageAccountRow(
                        Account(id = 2, name = "Old Card", typeKey = "card", currencyCode = "USD", openingBalance = Money.of("0", "USD"), archived = true),
                        hasTransactions = false,
                    ),
                ),
            ),
            onArchive = {}, onUnarchive = {}, onDelete = {}, onEdit = {}, onDone = {},
        )
    }
}
