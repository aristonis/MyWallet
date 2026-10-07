package org.aristonis.mywallet.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.components.EmptyState
import org.aristonis.mywallet.ui.components.MenuAction
import org.aristonis.mywallet.ui.components.OverflowMenu
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.theme.MyWalletTheme

/**
 * Manage Accounts: archive/unarchive and delete existing accounts. Archived accounts are listed here
 * (greyed) so they can be brought back. Delete is only offered for accounts with no transactions;
 * used accounts show an "archive instead" hint. [onDone] returns to Home. No nav library.
 */
@Composable
fun ManageAccountsScreen(
    onDone: () -> Unit,
    onAddAccount: () -> Unit,
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
        onAddAccount = onAddAccount,
        onDone = onDone,
    )
}

@Composable
internal fun ManageAccountsContent(
    state: ManageAccountsUiState,
    onArchive: (Long) -> Unit,
    onUnarchive: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onAddAccount: () -> Unit,
    onDone: () -> Unit,
) {
    // Deleting an account is destructive, so it goes through a confirm dialog keyed on the pending id.
    var pendingDelete by remember { mutableStateOf<ManageAccountRow?>(null) }

    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.manage_accounts_title), onBack = onDone)
        },
        floatingActionButton = {
            // Adding an account lives here rather than on Home: it is a setup action, and Home's one
            // button belongs to the thing users do every day.
            ExtendedFloatingActionButton(onClick = onAddAccount) {
                Icon(imageVector = WalletIcons.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.home_add_account),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { message ->
                Text(
                    message.text(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            when {
                state.isLoading -> CircularProgressIndicator()

                state.rows.isEmpty() -> EmptyState(
                    message = stringResource(R.string.manage_accounts_empty),
                    actionLabel = stringResource(R.string.home_create_first_account),
                    onAction = onAddAccount,
                )

                else -> LazyColumn(
                    // Without a bound the list asks for the height of all its rows, pushing the last
                    // of them past the bottom of the screen where no scroll can reach them.
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
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
            title = { Text(stringResource(R.string.delete_account_title, row.account.name)) },
            text = { Text(stringResource(R.string.delete_account_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(row.account.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
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
                    Text(
                        text = stringResource(R.string.account_archived),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OverflowMenu(
                    contentDescription = stringResource(R.string.cd_row_actions, row.account.name),
                    actions = accountActions(row, onEdit, onArchive, onUnarchive, onDeleteRequest),
                )
            }
            Text(
                stringResource(
                    R.string.account_type_and_currency,
                    accountTypeLabel(row.account.typeKey),
                    row.account.currencyCode,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Deleting a used account would orphan its history, so the hint replaces the action
            // rather than sitting beside a disabled one the user would keep trying.
            if (row.hasTransactions) {
                Text(
                    stringResource(R.string.account_archive_instead),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Every account can be edited (name and type always; currency only while it has no transactions —
 * the editor locks that field), so Edit is offered unconditionally. Archive and Unarchive are the
 * same slot in two states, and Delete only appears where it would not orphan a history.
 */
@Composable
private fun accountActions(
    row: ManageAccountRow,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDeleteRequest: () -> Unit,
): List<MenuAction> = buildList {
    add(MenuAction(stringResource(R.string.action_edit), onEdit, WalletIcons.Edit))
    if (row.account.archived) {
        add(MenuAction(stringResource(R.string.action_unarchive), onUnarchive, WalletIcons.Unarchive))
    } else {
        add(MenuAction(stringResource(R.string.action_archive), onArchive, WalletIcons.Archive))
    }
    if (!row.hasTransactions) {
        add(
            MenuAction(
                label = stringResource(R.string.action_delete),
                onClick = onDeleteRequest,
                icon = WalletIcons.Delete,
                isDestructive = true,
            ),
        )
    }
}

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
            onArchive = {}, onUnarchive = {}, onDelete = {}, onEdit = {}, onAddAccount = {}, onDone = {},
        )
    }
}
