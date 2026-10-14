package org.aristonis.mywallet.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.components.EmptyState
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme

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
    onOpenAccount: (Long) -> Unit,
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
        onOpen = onOpenAccount,
        onAddAccount = onAddAccount,
        onDone = onDone,
        onRetry = viewModel::retry,
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
    onRetry: () -> Unit,
    onOpen: (Long) -> Unit = {},
) {
    val dimensions = WalletTheme.dimensions
    // Deleting an account is destructive, so it goes through a confirm dialog keyed on the pending id.
    var pendingDelete by remember { mutableStateOf<ManageAccountRow?>(null) }

    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.manage_accounts_title), onBack = onDone)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(dimensions.screenPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(dimensions.regionGap),
        ) {
            state.error?.let { message ->
                Text(
                    message.text(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.manage_accounts_active),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAddAccount, modifier = Modifier.heightIn(min = dimensions.minTouchTarget)) {
                    Icon(imageVector = WalletIcons.Add, contentDescription = null)
                    Text(stringResource(R.string.home_add_account), modifier = Modifier.padding(start = dimensions.relatedGap))
                }
            }

            when {
                state.isLoading -> CircularProgressIndicator()

                state.loadFailed -> LoadFailedState(
                    message = (state.loadFailure ?: UiMessage.DataUnreadable).text(),
                    onRetry = onRetry,
                )

                state.rows.isEmpty() -> EmptyState(
                    message = stringResource(R.string.manage_accounts_empty),
                )

                else -> LazyColumn(
                    // Without a bound the list asks for the height of all its rows, pushing the last
                    // of them past the bottom of the screen where no scroll can reach them.
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = dimensions.sectionGap),
                ) {
                    val active = state.rows.filterNot { it.account.archived }
                    val archived = state.rows.filter { it.account.archived }
                    if (active.isEmpty()) {
                        item(key = "no-active") {
                            Text(
                                stringResource(R.string.manage_accounts_no_active),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(active.size, key = { active[it].account.id }) { index ->
                        val row = active[index]
                        AccountManageRow(
                            row = row,
                            isFirst = index == 0,
                            isLast = index == active.lastIndex,
                            onEdit = { onEdit(row.account.id) },
                            onOpen = { onOpen(row.account.id) },
                            onArchive = { onArchive(row.account.id) },
                            onUnarchive = { onUnarchive(row.account.id) },
                            onDeleteRequest = { pendingDelete = row },
                        )
                    }
                    if (archived.isNotEmpty()) {
                        item(key = "archived-heading") {
                            Text(
                                stringResource(R.string.account_archived),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = dimensions.sectionGap),
                            )
                        }
                    }
                    items(archived.size, key = { archived[it].account.id }) { index ->
                        val row = archived[index]
                        AccountManageRow(
                            row = row,
                            isFirst = index == 0,
                            isLast = index == archived.lastIndex,
                            onEdit = { onEdit(row.account.id) },
                            onOpen = { onOpen(row.account.id) },
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

private class ManageAccountsPreviewStates : PreviewParameterProvider<ManageAccountsUiState> {
    override val values = sequenceOf(
        ManageAccountsUiState(
            isLoading = false,
            rows = listOf(
                ManageAccountRow(
                    Account(1, "Everyday wallet", "cash", "USD", Money.of("100", "USD")),
                    hasTransactions = true,
                    balance = Money.of("840", "USD"),
                    balanceDisplay = "840.00 USD",
                ),
                ManageAccountRow(
                    Account(2, "Bank account", "bank", "USD", Money.of("0", "USD")),
                    hasTransactions = false,
                    balance = Money.of("1960", "USD"),
                    balanceDisplay = "1,960.00 USD",
                ),
            ),
        ),
        ManageAccountsUiState(rows = emptyList(), isLoading = false),
    )
}

@Preview(showBackground = true, widthDp = 360)
@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ManageAccountsPreview(@PreviewParameter(ManageAccountsPreviewStates::class) state: ManageAccountsUiState) {
    MyWalletTheme(dynamicColor = false) {
        ManageAccountsContent(
            state = state,
            onArchive = {}, onUnarchive = {}, onDelete = {}, onEdit = {}, onAddAccount = {}, onDone = {}, onRetry = {},
        )
    }
}
