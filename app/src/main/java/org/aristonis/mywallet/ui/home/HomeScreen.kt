package org.aristonis.mywallet.ui.home

// Amounts arrive pre-formatted from the view-model (per currency + locale), so this screen never
// formats money itself — it only decides how the finished text is drawn.

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.account.accountTypeLabel
import org.aristonis.mywallet.ui.components.EmptyState
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.icons.accountTypeIcon
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme

private val ACCOUNT_STACK_WIDTH = 300.dp
private const val LARGE_TEXT_SCALE = 1.8f

@Composable
fun HomeScreen(
    onAddTransaction: () -> Unit,
    onAddAccount: () -> Unit,
    onManageAccounts: () -> Unit,
    onManageRates: () -> Unit,
    onSettings: () -> Unit,
    onDebts: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReportDrawnWhen { !state.isLoading }
    HomeContent(
        state = state,
        onAddTransaction = onAddTransaction,
        onAddAccount = onAddAccount,
        onManageAccounts = onManageAccounts,
        onManageRates = onManageRates,
        onSettings = onSettings,
        onDebts = onDebts,
        onOpenAccount = onOpenAccount,
        onRetry = viewModel::retry,
    )
}

/** [onRetry] reads the wallet again after a failed read. */
@Composable
internal fun HomeContent(
    state: HomeUiState,
    onAddTransaction: () -> Unit,
    onAddAccount: () -> Unit,
    onManageAccounts: () -> Unit,
    onManageRates: () -> Unit,
    onSettings: () -> Unit,
    onDebts: () -> Unit,
    onRetry: () -> Unit,
    onOpenAccount: (Long) -> Unit = {},
) {
    val dimensions = WalletTheme.dimensions
    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.home_title)) {
                TextButton(onClick = onDebts) { Text(stringResource(R.string.debts_title)) }
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = WalletIcons.Settings,
                        contentDescription = stringResource(R.string.home_settings),
                    )
                }
            }
        },
        bottomBar = {
            // Recording a transaction is the primary action, so it gets the FAB. Adding an account is
            // a rarer, setup-time action, so it lives behind Manage.
            Box(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(dimensions.screenPadding),
                contentAlignment = Alignment.CenterEnd,
            ) {
                // Primary, not primaryContainer: the balance card above already uses the container color,
                // and the one main action must stand apart from it, as the frame draws it.
                ExtendedFloatingActionButton(
                    onClick = onAddTransaction,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(imageVector = WalletIcons.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.home_add_transaction),
                        modifier = Modifier.padding(start = dimensions.relatedGap),
                    )
                }
            }
        },
    ) { innerPadding ->
        // One list, not a column wrapping a list: the hero scrolls away with the accounts, and the
        // accounts get a real height instead of asking for the sum of all their rows.
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(dimensions.screenPadding),
        ) {
            // A failed read has no figure coming, so no spinner promising one; the message below says why.
            if (!state.loadFailed) {
                item(key = "balance") {
                    Column(modifier = Modifier.padding(bottom = dimensions.regionGap)) {
                        HomeSummaryHero(state.summary, onManageRates, onDebts)
                    }
                }
            }
            if (!state.loadFailed && state.debtsBelowZero.isNotEmpty()) {
                item(key = "negative-debts") {
                    Box(Modifier.padding(bottom = dimensions.regionGap)) {
                        StatusCard(StatusTone.WARNING, stringResource(R.string.home_debts_below_zero),
                            iconDescription = stringResource(R.string.cd_warning), onClick = onDebts)
                    }
                }
            }
            item(key = "accounts-header") {
                Box(Modifier.padding(bottom = dimensions.relatedGap)) {
                    SectionHeader(title = stringResource(R.string.home_accounts)) {
                        if (state.accounts.isNotEmpty()) {
                            TextButton(onClick = onManageAccounts) { Text(stringResource(R.string.home_manage)) }
                        }
                    }
                }
            }
            when {
                // A failed read is not an empty wallet: offering a first account would suggest the
                // user's accounts are gone.
                state.loadFailed -> item(key = "accounts-unreadable") {
                    LoadFailedState(
                        message = stringResource(R.string.home_data_unreadable),
                        onRetry = onRetry,
                        modifier = Modifier.padding(top = dimensions.regionGap),
                    )
                }

                // Before the first read an empty list means "unknown", not "no accounts", so no prompt
                // yet. The summary above already shows the screen is loading; one spinner is enough.
                !state.isLoading && state.accounts.isEmpty() -> item(key = "accounts-empty") {
                    EmptyState(
                        message = stringResource(R.string.home_no_accounts),
                        actionLabel = stringResource(R.string.home_create_first_account),
                        onAction = onAddAccount,
                        modifier = Modifier.padding(top = dimensions.regionGap),
                    )
                }

                else -> itemsIndexed(state.accounts, key = { _, row -> row.account.id }) { index, row ->
                    val isFirst = index == 0
                    val isLast = index == state.accounts.lastIndex
                    val corners = RoundedCornerShape(
                        topStart = if (isFirst) dimensions.cornerMedium else 0.dp,
                        topEnd = if (isFirst) dimensions.cornerMedium else 0.dp,
                        bottomStart = if (isLast) dimensions.cornerMedium else 0.dp,
                        bottomEnd = if (isLast) dimensions.cornerMedium else 0.dp,
                    )
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, corners)) {
                        AccountLedgerRow(row, onManageRates, onOpen = { onOpenAccount(row.account.id) })
                        if (!isLast) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = dimensions.cardPadding),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountLedgerRow(row: AccountRow, onManageRates: () -> Unit, onOpen: () -> Unit) {
    val base = row.base // local val so Kotlin can smart-cast after the null check
    val dimensions = WalletTheme.dimensions
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rowTextStyle = MaterialTheme.typography.titleMedium
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val nameWidth = with(density) { textMeasurer.measure(row.account.name, style = rowTextStyle).size.width.toDp() }
        val amountWidth = with(density) { textMeasurer.measure(row.nativeDisplay, style = rowTextStyle).size.width.toDp() }
        val availableForAmount = maxWidth - dimensions.cardPadding * 2 - dimensions.iconSize -
            dimensions.iconContentGap - nameWidth.coerceAtMost(maxWidth / 2)
        val stack = maxWidth / density.fontScale < ACCOUNT_STACK_WIDTH || amountWidth > availableForAmount
        Column(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.account_detail_open), onClick = onOpen).heightIn(min = 64.dp)
            .padding(horizontal = dimensions.cardPadding, vertical = dimensions.relatedGap),
            verticalArrangement = Arrangement.spacedBy(dimensions.grid)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(dimensions.iconContentGap)) {
                Icon(accountTypeIcon(row.account.typeKey), contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(row.account.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.account_type_and_currency,
                        accountTypeLabel(row.account.typeKey), row.account.currencyCode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!stack) MoneyText(row.nativeDisplay, style = MaterialTheme.typography.titleMedium)
            }
            if (stack) MoneyText(row.nativeDisplay, style = MaterialTheme.typography.titleMedium)
            when {
                base == null -> TextButton(onClick = onManageRates) {
                    Text(stringResource(R.string.home_account_missing_rate, row.native.currencyCode),
                        color = WalletTheme.colors.warning)
                }
                base.currencyCode != row.native.currencyCode -> MoneyText(
                    stringResource(R.string.home_account_converted, row.baseDisplay.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CreditLines(row)
        }
    }
}

/** The credit card lines: what is owed, what is still available, and a warning when over the limit. */
@Composable
private fun CreditLines(row: AccountRow) {
    row.owedDisplay?.let { Text(stringResource(R.string.home_credit_owed, bidiIsolate(it))) }
    row.availableDisplay?.let { Text(stringResource(R.string.home_credit_available, bidiIsolate(it))) }
    if (row.credit?.overLimit == true) {
        StatusCard(StatusTone.WARNING,
            stringResource(R.string.home_credit_over_limit, bidiIsolate(row.overLimitDisplay.orEmpty())),
            iconDescription = stringResource(R.string.cd_warning))
    }
}

private class HomePreviewSamples : PreviewParameterProvider<HomeUiState> {
    override val values: Sequence<HomeUiState> = sequenceOf(
        HomeUiState(
            summary = HomeSummaryState.Amounts("1,275.00 USD", "0.00 USD", "0.00 USD", "1,275.00 USD"),
            accounts = listOf(
                AccountRow(
                    account = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD",
                        openingBalance = Money.of("1000", "USD")),
                    native = Money.of("1000", "USD"), base = Money.of("1000", "USD"),
                    nativeDisplay = "1,000.00 USD", baseDisplay = "1,000.00 USD",
                ),
                AccountRow(
                    account = Account(id = 2, name = "Euro Savings", typeKey = "bank", currencyCode = "EUR",
                        openingBalance = Money.of("250", "EUR")),
                    native = Money.of("250", "EUR"), base = Money.of("275.00", "USD"),
                    nativeDisplay = "250.00 EUR", baseDisplay = "275.00 USD",
                ),
            ),
            baseCurrencyCode = "USD",
        ),
        HomeUiState(isLoading = false),
        HomeUiState(isLoading = false, loadFailed = true),
        HomeUiState(
            summary = HomeSummaryState.Amounts("100.00 USD", "0.00 USD", "0.00 USD", "100.00 USD"),
            accounts = listOf(
                AccountRow(
                    account = Account(id = 3, name = "Everyday wallet", typeKey = "cash",
                        currencyCode = "USD", openingBalance = Money.of("12345678901", "USD")),
                    native = Money.of("12345678901", "USD"),
                    base = Money.of("12345678901", "USD"),
                    nativeDisplay = "12,345,678,901.00 USD",
                    baseDisplay = "12,345,678,901.00 USD",
                ),
            ),
            baseCurrencyCode = "USD",
        ),
    )
}

@Preview(name = "Home light", showBackground = true, widthDp = 360)
@Preview(name = "Home dark", showBackground = true, widthDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Home large text", showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun HomePreview(@PreviewParameter(HomePreviewSamples::class) state: HomeUiState) {
    MyWalletTheme(dynamicColor = false) {
        HomeContent(
            onAddTransaction = {},
            onAddAccount = {},
            onManageAccounts = {},
            onManageRates = {},
            onSettings = {},
            onDebts = {},
            state = state,
            onRetry = {},
        )
    }
}
