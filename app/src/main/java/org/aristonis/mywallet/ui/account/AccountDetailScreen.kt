package org.aristonis.mywallet.ui.account

// Amounts arrive pre-formatted from the view model (per currency and locale); this screen only lays
// them out and never decides a figure.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.AristonisLoader
import org.aristonis.mywallet.ui.components.EmptyState
import org.aristonis.mywallet.ui.components.MetricCell
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.rememberDayFormatter
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.WalletTheme
import org.aristonis.mywallet.ui.transaction.TransactionRow
import org.aristonis.mywallet.ui.transaction.TransactionRowItem
import java.time.LocalDate

private const val TAG_BALANCE = "account-detail-balance"
private const val TAG_BASE_BALANCE = "account-detail-base-balance"
private const val TAG_MISSING_RATE = "account-detail-missing-rate"
private const val TAG_CREDIT_LIMIT = "account-detail-credit-limit"
private const val TAG_AVAILABLE = "account-detail-available"
private const val TAG_OVER_LIMIT = "account-detail-over-limit"
private const val TAG_ARCHIVED = "account-detail-archived"
private const val TAG_NO_ENTRIES = "account-detail-no-entries"
private const val TAG_LOAD_MORE = "account-detail-load-more"
private const val TAG_LOADING = "account-detail-loading"
private const val TAG_NOT_FOUND = "account-detail-not-found"
private const val TAG_RETRY = "account-detail-retry"
private const val TAG_ADD = "account-detail-add"
private const val TAG_PAY_CARD = "account-detail-pay-card"
private const val TAG_EDIT = "account-detail-edit"

private fun entryTag(id: Long) = "account-detail-entry-$id"

/** Key of the item that stands for the entries not loaded yet; no entry id can take it. */
private const val LOAD_MORE_KEY = "load-more"

/**
 * Text-adjusted widths below which side-by-side content stacks. Dividing by the font scale first
 * means a phone with large text stacks even though its dp width has not changed.
 */
/** From this text scale the stacked action buttons move into the list. */
private const val LARGE_TEXT_SCALE = 1.5f
private val ACTIONS_ROW_WIDTH = 300.dp
private val CREDIT_ACTIONS_ROW_WIDTH = 420.dp

/** Whether the action buttons no longer fit side by side at this width and text size. */
private fun actionsStacked(isCredit: Boolean, width: Dp, fontScale: Float): Boolean =
    width / fontScale < if (isCredit) CREDIT_ACTIONS_ROW_WIDTH else ACTIONS_ROW_WIDTH
private val CREDIT_CELLS_ROW_WIDTH = 300.dp

/** OpenType tabular figures, as [MoneyText] draws them, so the hero is measured as it will be drawn. */
private const val TABULAR_FIGURES = "tnum"

/**
 * The account page wired to its view model. The actions are only shown once an account has been
 * read, so the id they pass on is always that account's own.
 */
@Composable
internal fun AccountDetailScreen(
    onBack: () -> Unit,
    onAddTransaction: (Long) -> Unit,
    onPayCard: (Long) -> Unit,
    onEditAccount: (Long) -> Unit,
    onOpenEntry: (Long) -> Unit,
    onManageRates: () -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountDetailContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onAddTransaction = { state.header?.let { onAddTransaction(it.id) } },
        onPayCard = { state.header?.let { onPayCard(it.id) } },
        onEditAccount = { state.header?.let { onEditAccount(it.id) } },
        onOpenEntry = onOpenEntry,
        onLoadMore = viewModel::loadMore,
        onManageRates = onManageRates,
    )
}

/**
 * The account page, stateless. A credit account shows its credit block and Pay card; every account
 * shows Add transaction and Edit account. [onOpenEntry] is called with an entry id.
 *
 * Loading, a missing account and a failed read are three different screens, and none of them shows
 * a balance: a zero there would read as a real figure. The actions sit below the list rather than
 * over it, so the last entry can always be scrolled clear of them.
 */
@Composable
internal fun AccountDetailContent(
    state: AccountDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAddTransaction: () -> Unit,
    onPayCard: () -> Unit,
    onEditAccount: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onManageRates: () -> Unit,
) {
    val header = state.header
    val failure = state.loadFailure
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Stacked buttons at large text take about half the screen, so there they follow the entries
        // instead of sitting over them; otherwise they stay pinned where the thumb is.
        val stacked = header != null && actionsStacked(header.credit != null, maxWidth, fontScale)
        val actionsInList = stacked && fontScale >= LARGE_TEXT_SCALE
        val actions: @Composable () -> Unit = {
            // Only an account that was read has anything to act on.
            if (header != null) {
                AccountActions(
                    isCredit = header.credit != null,
                    canAddEntries = !header.archived,
                    stacked = stacked,
                    onAddTransaction = onAddTransaction,
                    onPayCard = onPayCard,
                    onEditAccount = onEditAccount,
                )
            }
        }
        Scaffold(
            topBar = {
                WalletTopAppBar(title = header?.name ?: stringResource(R.string.account_detail_title), onBack = onBack)
            },
            bottomBar = { if (!actionsInList) actions() },
        ) { innerPadding ->
            val body = Modifier.padding(innerPadding).fillMaxSize()
            when {
                header != null -> AccountDetailList(
                    state = state,
                    header = header,
                    onOpenEntry = onOpenEntry,
                    onLoadMore = onLoadMore,
                    onManageRates = onManageRates,
                    footer = if (actionsInList) actions else null,
                    modifier = body,
                )
                failure != null -> ReadFailure(failure = failure, onRetry = onRetry, modifier = body)
                state.notFound -> NotFound(modifier = body)
                else -> Loading(modifier = body)
            }
        }
    }
}

@Composable
private fun Loading(modifier: Modifier) {
    AristonisLoader(modifier.testTag(TAG_LOADING))
}

/** Nothing to retry: the accounts were read, and this one is not among them. */
@Composable
private fun NotFound(modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(WalletTheme.dimensions.screenPadding)) {
        StatusCard(
            tone = StatusTone.ERROR,
            message = stringResource(R.string.account_detail_not_found),
            modifier = Modifier.testTag(TAG_NOT_FOUND),
        )
    }
}

/**
 * The read failed, so no amount is known and none is guessed. Built here rather than with
 * LoadFailedState because the Retry button itself needs its own tag, which that component does not
 * take.
 */
@Composable
private fun ReadFailure(failure: UiMessage, onRetry: () -> Unit, modifier: Modifier) {
    val dimensions = WalletTheme.dimensions
    // A row the app cannot read gets wording that says the data is safe; anything else is generic.
    val message = when (failure) {
        UiMessage.DataUnreadable -> stringResource(R.string.account_detail_unreadable)
        else -> failure.text()
    }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(dimensions.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimensions.regionGap),
    ) {
        StatusCard(tone = StatusTone.ERROR, message = message)
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = dimensions.minTouchTarget).testTag(TAG_RETRY)) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

/**
 * One list, so the balance scrolls away with the entries and the entries get the whole height. The
 * entries are grouped under their day in the order they arrive, newest first; the rows themselves
 * carry no date.
 */
@Composable
private fun AccountDetailList(
    state: AccountDetailUiState,
    header: AccountDetailHeader,
    onOpenEntry: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onManageRates: () -> Unit,
    footer: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val dimensions = WalletTheme.dimensions
    val formatDay = rememberDayFormatter(currentYear = LocalDate.now().year)
    val days = remember(state.entries) { state.entries.groupBy { it.date }.toList() }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = dimensions.screenPadding,
            end = dimensions.screenPadding,
            top = dimensions.screenPadding,
            bottom = dimensions.sectionGap,
        ),
    ) {
        item(key = "hero") { AccountBalanceHero(header, Modifier.testTag(TAG_BALANCE)) }
        if (header.missingRate) {
            item(key = "missing-rate") {
                MissingRateNotice(
                    currencyCode = header.currencyCode,
                    onManageRates = onManageRates,
                    modifier = Modifier.padding(top = dimensions.regionGap).testTag(TAG_MISSING_RATE),
                )
            }
        }
        header.credit?.let { credit ->
            item(key = "credit") { CreditBlock(credit, Modifier.padding(top = dimensions.regionGap)) }
        }
        if (header.archived) {
            item(key = "archived") {
                StatusCard(
                    tone = StatusTone.INFO,
                    message = stringResource(R.string.account_detail_archived),
                    modifier = Modifier.padding(top = dimensions.regionGap).testTag(TAG_ARCHIVED),
                )
            }
        }
        item(key = "entries-header") {
            SectionHeader(
                title = stringResource(R.string.account_detail_entries),
                modifier = Modifier.padding(top = dimensions.sectionGap),
            )
        }
        if (state.entries.isEmpty()) {
            item(key = "no-entries") {
                EmptyState(
                    message = stringResource(R.string.account_detail_no_entries),
                    modifier = Modifier.padding(top = dimensions.regionGap).testTag(TAG_NO_ENTRIES),
                )
            }
        }
        days.forEach { (date, rows) ->
            item(key = "day-$date") { DayLabel(formatDay(date)) }
            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                EntryRow(row = row, isFirst = index == 0, isLast = index == rows.lastIndex, onOpenEntry = onOpenEntry)
            }
        }
        if (state.hasMore) {
            item(key = LOAD_MORE_KEY) {
                LoadMore(rowCount = state.entries.size, isLoadingMore = state.isLoadingMore, onLoadMore = onLoadMore)
            }
        }
        if (footer != null) item(key = "actions") { footer() }
    }
}

/**
 * What the account holds now. A credit account leads with what is owed, since that is the figure a
 * card statement shows; its signed balance stays beside it so a card paid past zero still shows the
 * credit. The converted line follows the native one and never stands in for it.
 */
@Composable
private fun AccountBalanceHero(header: AccountDetailHeader, modifier: Modifier) {
    val dimensions = WalletTheme.dimensions
    val credit = header.credit
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(dimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
        ) {
            Text(
                text = stringResource(
                    R.string.account_detail_type_currency,
                    accountTypeLabel(header.typeKey),
                    header.currencyCode,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(
                    if (credit != null) R.string.account_detail_owed else R.string.account_detail_current_balance,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            HeroAmount(credit?.owedDisplay ?: header.nativeDisplay)
            if (credit != null) {
                Text(
                    text = stringResource(R.string.account_detail_card_balance, bidiIsolate(header.nativeDisplay)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            header.baseDisplay?.let { base ->
                MoneyText(
                    amount = stringResource(R.string.account_detail_converted, base),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TAG_BASE_BALANCE),
                )
            }
        }
    }
}

/**
 * The balance in the largest style it fits on one line in, so a long figure steps down rather than
 * breaking between its digits. Past the smallest step it wraps, and every digit stays visible.
 */
@Composable
private fun HeroAmount(amount: String) {
    val measurer = rememberTextMeasurer()
    val typography = MaterialTheme.typography
    val steps = listOf(typography.displaySmall, typography.headlineSmall, typography.titleLarge)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        val style = remember(amount, steps, available, measurer) {
            steps.firstOrNull { step ->
                val drawn = step.copy(fontFeatureSettings = TABULAR_FIGURES)
                measurer.measure(amount, style = drawn, maxLines = 1).size.width <= available
            } ?: steps.last()
        }
        MoneyText(amount = amount, style = style)
    }
}

/** The base value cannot be shown without a rate; the way to fix that is one tap away. */
@Composable
private fun MissingRateNotice(currencyCode: String, onManageRates: () -> Unit, modifier: Modifier) {
    val dimensions = WalletTheme.dimensions
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap)) {
        StatusCard(
            tone = StatusTone.WARNING,
            message = stringResource(R.string.account_detail_missing_rate, currencyCode),
            iconDescription = stringResource(R.string.cd_warning),
        )
        TextButton(onClick = onManageRates, modifier = Modifier.heightIn(min = dimensions.minTouchTarget)) {
            Text(stringResource(R.string.account_detail_manage_rates))
        }
    }
}

/**
 * The card's limit and the credit left. Available is left out when no limit is set, since there is
 * nothing to subtract from; over the limit, a warning says so in words as well as colour.
 */
@Composable
private fun CreditBlock(credit: CreditDetail, modifier: Modifier) {
    val dimensions = WalletTheme.dimensions
    val limitLabel = stringResource(R.string.account_detail_credit_limit)
    val availableLabel = stringResource(R.string.account_detail_available)
    val noLimit = stringResource(R.string.account_detail_no_limit)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stack = maxWidth / LocalDensity.current.fontScale < CREDIT_CELLS_ROW_WIDTH
            if (stack) {
                Column(verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap)) {
                    MetricCell(
                        limitLabel, credit.limitDisplay, noLimit, Modifier.fillMaxWidth().testTag(TAG_CREDIT_LIMIT),
                    )
                    credit.availableDisplay?.let {
                        MetricCell(availableLabel, it, noLimit, Modifier.fillMaxWidth().testTag(TAG_AVAILABLE))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(dimensions.relatedGap)) {
                    MetricCell(limitLabel, credit.limitDisplay, noLimit, Modifier.weight(1f).testTag(TAG_CREDIT_LIMIT))
                    credit.availableDisplay?.let {
                        MetricCell(availableLabel, it, noLimit, Modifier.weight(1f).testTag(TAG_AVAILABLE))
                    }
                }
            }
        }
        if (credit.overLimit) {
            StatusCard(
                tone = StatusTone.WARNING,
                message = stringResource(R.string.account_detail_over_limit),
                modifier = Modifier.testTag(TAG_OVER_LIMIT),
                iconDescription = stringResource(R.string.cd_warning),
            )
        }
    }
}

@Composable
private fun DayLabel(text: String) {
    val dimensions = WalletTheme.dimensions
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = dimensions.regionGap, bottom = dimensions.grid),
    )
}

/** One entry in its day's group: rounded at the group's ends, divided inside it. */
@Composable
private fun EntryRow(row: TransactionRow, isFirst: Boolean, isLast: Boolean, onOpenEntry: (Long) -> Unit) {
    val dimensions = WalletTheme.dimensions
    val corners = RoundedCornerShape(
        topStart = if (isFirst) dimensions.cornerMedium else 0.dp,
        topEnd = if (isFirst) dimensions.cornerMedium else 0.dp,
        bottomStart = if (isLast) dimensions.cornerMedium else 0.dp,
        bottomEnd = if (isLast) dimensions.cornerMedium else 0.dp,
    )
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, corners)) {
        Box(Modifier.fillMaxWidth().testTag(entryTag(row.id))) {
            TransactionRowItem(row = row, onClick = { onOpenEntry(row.id) })
        }
        if (!isLast) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = dimensions.cardPadding),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * Stands for the entries not loaded yet. Coming into view asks for the next page, and asks again
 * whenever a page has finished loading while it is still in view. The button covers a request the
 * view model turned away and anyone moving through the list by focus rather than by scrolling.
 */
@Composable
private fun LoadMore(rowCount: Int, isLoadingMore: Boolean, onLoadMore: () -> Unit) {
    val dimensions = WalletTheme.dimensions
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(rowCount, isLoadingMore) {
        if (!isLoadingMore) currentOnLoadMore()
    }
    Box(Modifier.fillMaxWidth().padding(top = dimensions.regionGap), contentAlignment = Alignment.Center) {
        if (isLoadingMore) {
            CircularProgressIndicator()
        } else {
            TextButton(
                onClick = onLoadMore,
                modifier = Modifier.heightIn(min = dimensions.minTouchTarget).testTag(TAG_LOAD_MORE),
            ) {
                Text(stringResource(R.string.account_detail_load_more))
            }
        }
    }
}

/**
 * Add transaction leads; Pay card follows for a credit account; Edit account is the quiet one. They
 * share a row when the text-adjusted width allows and stack full width otherwise, so no label is
 * squeezed onto two lines at large text.
 */
@Composable
private fun AccountActions(
    isCredit: Boolean,
    canAddEntries: Boolean,
    stacked: Boolean,
    onAddTransaction: () -> Unit,
    onPayCard: () -> Unit,
    onEditAccount: () -> Unit,
) {
    val dimensions = WalletTheme.dimensions
    // Child screens clear the system navigation bar themselves; a Scaffold bottom bar does not.
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = dimensions.screenPadding, vertical = dimensions.relatedGap),
    ) {
        val target = Modifier.heightIn(min = dimensions.minTouchTarget)
        val add = stringResource(R.string.account_detail_add)
        val pay = stringResource(R.string.account_detail_pay_card)
        val edit = stringResource(R.string.account_detail_edit)
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap)) {
                // An archived account cannot take new entries; the form would open with no account.
                if (canAddEntries) {
                    Button(onClick = onAddTransaction, modifier = target.fillMaxWidth().testTag(TAG_ADD)) { Text(add) }
                }
                if (isCredit && canAddEntries) {
                    FilledTonalButton(onClick = onPayCard, modifier = target.fillMaxWidth().testTag(TAG_PAY_CARD)) {
                        Text(pay)
                    }
                }
                TextButton(onClick = onEditAccount, modifier = target.fillMaxWidth().testTag(TAG_EDIT)) { Text(edit) }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canAddEntries) {
                    Button(onClick = onAddTransaction, modifier = target.weight(1f).testTag(TAG_ADD)) { Text(add) }
                }
                if (isCredit && canAddEntries) {
                    FilledTonalButton(onClick = onPayCard, modifier = target.weight(1f).testTag(TAG_PAY_CARD)) {
                        Text(pay)
                    }
                }
                TextButton(onClick = onEditAccount, modifier = target.testTag(TAG_EDIT)) { Text(edit) }
            }
        }
    }
}
