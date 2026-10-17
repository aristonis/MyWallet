package org.aristonis.mywallet.ui.transaction

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.ui.components.AristonisLoader
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.ui.components.MenuAction
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.OverflowMenu
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletListRow
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.AmountRole
import org.aristonis.mywallet.ui.format.amountRole
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.rememberDateFormatter
import org.aristonis.mywallet.ui.format.signedAmount
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.text
import org.aristonis.mywallet.ui.theme.WalletTheme
import org.aristonis.mywallet.ui.theme.amountColor

private const val LOADING_TAG = "transaction-detail-loading"
private const val NOT_FOUND_TAG = "transaction-detail-not-found"
private const val RETRY_TAG = "transaction-detail-retry"
private const val HERO_AMOUNT_TAG = "transaction-detail-hero-amount"
private const val CATEGORY_TAG = "transaction-detail-category"
private const val ACCOUNT_TAG = "transaction-detail-account"
private const val DEST_ACCOUNT_TAG = "transaction-detail-dest-account"
private const val GROSS_TAG = "transaction-detail-gross"
private const val MOVEMENT_TAG = "transaction-detail-movement"
private const val DEDUCTION_TAG_PREFIX = "transaction-detail-deduction-"
private const val FEE_TAG = "transaction-detail-fee"
private const val DEBT_EFFECT_TAG = "transaction-detail-debt-effect"
private const val INTEREST_TAG = "transaction-detail-interest"
private const val NOTE_TAG = "transaction-detail-note"
private const val DELETE_ERROR_TAG = "transaction-detail-delete-error"
private const val EDIT_TAG = "transaction-detail-edit"
private const val DELETE_TAG = "transaction-detail-delete"
private const val DELETE_CONFIRM_TAG = "transaction-detail-delete-confirm"
private const val DELETE_CANCEL_TAG = "transaction-detail-delete-cancel"
private const val UNDO_TAG = "transaction-detail-undo"

/**
 * The read-only transaction page, wired to its view model. [onEdit] gets the entry's id; [onDeleted]
 * runs once a delete is let go (the undo window closed without Undo), so the caller can leave.
 */
@Composable
internal fun TransactionDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onDeleted: () -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnDeleted by rememberUpdatedState(onDeleted)
    // The page leaves the composition while the editor is open on top of it; reading the entry again
    // when it comes back shows the edit (or that the entry was deleted there) instead of stale details.
    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(state.deleted) {
        if (state.deleted) {
            currentOnDeleted()
            viewModel.acknowledgeDeleted()
        }
    }
    TransactionDetailContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onEdit = { state.row?.let { row -> onEdit(row.id) } },
        onDelete = viewModel::delete,
        onUndoDelete = viewModel::undoDelete,
        onConfirmDelete = viewModel::confirmDelete,
    )
}

/**
 * The read-only transaction page, stateless. Edit opens the editor; Delete asks for confirmation
 * first, then reports through [onDelete].
 */
@Composable
internal fun TransactionDetailContent(
    state: TransactionDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUndoDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    UndoDeleteEffect(state, snackbarHostState, onUndoDelete, onConfirmDelete)
    var isConfirmingDelete by rememberSaveable { mutableStateOf(false) }
    val requestDelete = { isConfirmingDelete = true }
    val row = state.row
    Scaffold(
        topBar = {
            WalletTopAppBar(
                title = row?.let { subjectOf(it) } ?: stringResource(R.string.transaction_detail_title),
                onBack = onBack,
                actions = {
                    // The same two actions as the buttons below, reachable without scrolling.
                    if (state.canAct) {
                        OverflowMenu(
                            contentDescription = stringResource(R.string.transaction_detail_more_actions),
                            actions = listOf(
                                MenuAction(stringResource(R.string.action_edit), onEdit, WalletIcons.Edit),
                                MenuAction(
                                    stringResource(R.string.action_delete), requestDelete, WalletIcons.Delete,
                                    isDestructive = true,
                                ),
                            ),
                        )
                    }
                },
            )
        },
        snackbarHost = { UndoSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            val failure = state.loadFailure
            when {
                row != null -> DetailBody(state, row, onEdit, requestDelete)
                failure != null -> ReadFailed(failure, onRetry)
                state.notFound -> StatusCard(
                    tone = StatusTone.WARNING,
                    message = stringResource(R.string.msg_transaction_not_found),
                    modifier = Modifier.padding(WalletTheme.dimensions.screenPadding).testTag(NOT_FOUND_TAG),
                )
                else -> AristonisLoader(Modifier.fillMaxSize().testTag(LOADING_TAG))
            }
        }
    }
    // The dialog closes by itself if the entry stops being something that can be deleted.
    if (isConfirmingDelete && row != null && state.canAct) {
        TransactionDeleteDialog(
            row = row,
            onConfirm = {
                isConfirmingDelete = false
                onDelete()
            },
            onDismiss = { isConfirmingDelete = false },
        )
    }
}

/**
 * The undo window, as on the editor: the entry is already removed, Undo puts it back, and letting the
 * snackbar go confirms the delete. Hidden while an undo is being written and shown again if that undo
 * failed, so the owner can try once more.
 */
@Composable
private fun UndoDeleteEffect(
    state: TransactionDetailUiState,
    host: SnackbarHostState,
    onUndoDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val message = stringResource(R.string.transaction_deleted)
    val undoLabel = stringResource(R.string.action_undo)
    val currentOnUndo by rememberUpdatedState(onUndoDelete)
    val currentOnConfirm by rememberUpdatedState(onConfirmDelete)
    val pending = state.undoableDelete?.takeUnless { state.isDeleting }
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        val result = host.showSnackbar(message = message, actionLabel = undoLabel, duration = SnackbarDuration.Short)
        when (result) {
            SnackbarResult.ActionPerformed -> currentOnUndo()
            SnackbarResult.Dismissed -> currentOnConfirm()
        }
    }
}

/** The default snackbar, with an Undo action that keeps a full touch target and can be found by tests. */
@Composable
private fun UndoSnackbarHost(host: SnackbarHostState) {
    SnackbarHost(host) { data ->
        Snackbar(
            modifier = Modifier.padding(WalletTheme.dimensions.screenPadding),
            action = data.visuals.actionLabel?.let { label ->
                {
                    TextButton(
                        onClick = { data.performAction() },
                        modifier = Modifier.heightIn(min = WalletTheme.dimensions.minTouchTarget).testTag(UNDO_TAG),
                        colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                    ) { Text(label) }
                }
            },
        ) { Text(data.visuals.message) }
    }
}

@Composable
private fun ReadFailed(failure: UiMessage, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(WalletTheme.dimensions.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.screenPadding),
    ) {
        StatusCard(tone = StatusTone.ERROR, message = failure.text())
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = WalletTheme.dimensions.minTouchTarget).testTag(RETRY_TAG)) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

/** The whole page scrolls as one, so the Delete action stays reachable at any text size. */
@Composable
private fun DetailBody(state: TransactionDetailUiState, row: TransactionRow, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(WalletTheme.dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap),
    ) {
        Hero(row)
        SectionHeader(
            title = stringResource(R.string.transaction_detail_recorded),
            modifier = Modifier.padding(top = WalletTheme.dimensions.regionGap),
        )
        // The recorded fields read as one record, so they share one bordered surface.
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            // RecordedDetails emits bare rows; inside a Surface they need a column or they stack.
            Column { RecordedDetails(row) }
        }
        state.deleteError?.let { error ->
            StatusCard(tone = StatusTone.ERROR, message = error.text(), modifier = Modifier.testTag(DELETE_ERROR_TAG))
        }
        Button(
            onClick = onEdit,
            enabled = state.canAct,
            modifier = Modifier.fillMaxWidth().heightIn(min = WalletTheme.dimensions.minTouchTarget).testTag(EDIT_TAG),
        ) { Text(stringResource(R.string.edit_transaction_title)) }
        TextButton(
            onClick = onDelete,
            enabled = state.canAct,
            modifier = Modifier.fillMaxWidth().heightIn(min = WalletTheme.dimensions.minTouchTarget).testTag(DELETE_TAG),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.transaction_detail_delete)) }
    }
}

/**
 * Kind and date, the saved amount, and where it moved. The amount is the stored one with the sign
 * its kind carries; it wraps rather than shortens, so a long figure is never cut.
 */
@Composable
private fun Hero(row: TransactionRow) {
    val role = row.type.amountRole()
    val formatDate = rememberDateFormatter()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.grid)) {
        Text(
            text = stringResource(R.string.transaction_headline_with_note, kindLabel(row.type), bidiIsolate(formatDate(row.date))),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoneyText(
            amount = signedAmount(role, row.amountDisplay),
            modifier = Modifier.testTag(HERO_AMOUNT_TAG),
            style = MaterialTheme.typography.headlineMedium,
            color = amountColor(role),
        )
        if (role != AmountRole.NEUTRAL) row.countedBaseAmountDisplay?.let { BaseAmountText(it) }
        accountLine(row)?.let { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Each line appears only when the saved entry carries it; deductions are the receipt's own copies. */
@Composable
private fun RecordedDetails(row: TransactionRow) {
    val missing = stringResource(R.string.value_missing)
    if (row.type.hasCategorySubject()) {
        row.categoryLabel?.let { DetailRow(CATEGORY_TAG, stringResource(R.string.field_category), it.text()) }
    }
    if (row.type.movesAccount()) {
        val label = if (row.type == TransactionRowType.TRANSFER) R.string.field_from_account else R.string.field_account
        DetailRow(ACCOUNT_TAG, stringResource(label), row.accountName ?: missing)
    }
    if (row.type == TransactionRowType.TRANSFER) {
        DetailRow(DEST_ACCOUNT_TAG, stringResource(R.string.field_to_account), row.destAccountName ?: missing)
    }
    row.grossAmountDisplay?.let { gross ->
        DetailRow(GROSS_TAG, stringResource(R.string.transaction_detail_gross), trailing = gross)
    }
    MovementRow(row, missing)
    row.owedAmountDisplay?.let { owed ->
        DetailRow(
            "transaction-detail-owed", stringResource(R.string.transaction_detail_debt_effect),
            stringResource(R.string.transaction_owed_to, bidiIsolate(owed), bidiIsolate(row.owedTo ?: missing)),
        )
        row.paidNowDisplay?.let { paidNow ->
            DetailRow("transaction-detail-paid-now", stringResource(R.string.transaction_paid_now), trailing = paidNow)
        }
    }
    if (row.deductions.isNotEmpty()) {
        Text(stringResource(R.string.income_summary_deductions), style = MaterialTheme.typography.titleSmall)
        row.deductions.forEachIndexed { index, line -> DeductionRow(index, line) }
    }
    row.feeDisplay?.let { fee ->
        DetailRow(
            FEE_TAG, stringResource(R.string.transaction_detail_fee), baseLine(row.feeBaseAmountDisplay),
            trailing = signedAmount(AmountRole.EXPENSE, fee), role = AmountRole.EXPENSE,
        )
    }
    DebtRows(row)
    row.note?.takeIf { it.isNotBlank() }?.let { note ->
        DetailRow(NOTE_TAG, stringResource(R.string.transaction_detail_note), note)
    }
}

/**
 * What actually reached an account where that differs from the hero: an income after the deductions
 * paid at once, and the destination leg of a transfer in its own currency.
 */
@Composable
private fun MovementRow(row: TransactionRow, missing: String) {
    val label = stringResource(R.string.transaction_detail_movement)
    val movement = row.accountMovementDisplay
    val destAmount = row.destAmountDisplay
    when {
        movement != null -> DetailRow(
            MOVEMENT_TAG, label,
            if (row.type == TransactionRowType.EXPENSE) {
                stringResource(R.string.transaction_movement_from, bidiIsolate(row.accountName ?: missing))
            } else intoAccount(row.accountName, missing),
            trailing = signedAmount(row.type.amountRole(), movement), role = row.type.amountRole(),
        )
        row.type == TransactionRowType.TRANSFER && destAmount != null ->
            DetailRow(MOVEMENT_TAG, label, intoAccount(row.destAccountName, missing), trailing = destAmount)
    }
}

@Composable
private fun intoAccount(name: String?, missing: String): String =
    stringResource(R.string.transaction_detail_movement_into, bidiIsolate(name ?: missing))

@Composable
private fun DeductionRow(index: Int, line: TransactionDeductionRow) {
    val timing = stringResource(
        if (line.timing == DeductionTiming.NOW) R.string.income_deduction_now else R.string.income_deduction_later,
    )
    val base = baseLine(line.baseAmountDisplay)
    DetailRow(
        tag = "$DEDUCTION_TAG_PREFIX$index",
        headline = line.name,
        supporting = base?.let { stringResource(R.string.transaction_headline_with_note, timing, it) } ?: timing,
        trailing = signedAmount(AmountRole.EXPENSE, line.amountDisplay),
        role = AmountRole.EXPENSE,
    )
}

/**
 * The debt side of a debt-linked entry. The amount is the debt change for every kind except a
 * repayment: its saved amount is what the account paid, which can include interest, so the effect is
 * named without a figure and the interest gets its own line.
 */
@Composable
private fun DebtRows(row: TransactionRow) {
    val effect = row.type.debtEffect() ?: return
    val isRepayment = row.type == TransactionRowType.REPAYMENT_PAID || row.type == TransactionRowType.REPAYMENT_RECEIVED
    DetailRow(
        DEBT_EFFECT_TAG, stringResource(R.string.transaction_detail_debt_effect), stringResource(effect),
        trailing = row.amountDisplay.takeUnless { isRepayment },
    )
    val interest = row.countedAmountDisplay
    if (isRepayment && interest != null) {
        val role = row.countedAmountRole ?: AmountRole.NEUTRAL
        val base = baseLine(row.countedBaseAmountDisplay)
        val category = row.categoryLabel?.text()
        val supporting = if (category != null && base != null) {
            stringResource(R.string.transaction_headline_with_note, bidiIsolate(category), base)
        } else {
            category ?: base
        }
        DetailRow(
            INTEREST_TAG, stringResource(R.string.transaction_detail_interest),
            supporting = supporting,
            trailing = signedAmount(role, interest), role = role,
        )
    }
}

/**
 * One recorded fact. Its texts merge into one node, so it reads as a single line to a screen reader
 * and a test can check the label and the value together.
 */
@Composable
private fun DetailRow(
    tag: String,
    headline: String,
    supporting: String? = null,
    trailing: String? = null,
    role: AmountRole = AmountRole.NEUTRAL,
) {
    WalletListRow(
        headline = headline,
        supporting = supporting,
        modifier = Modifier.testTag(tag).semantics(mergeDescendants = true) {},
        trailing = trailing?.let { amount ->
            { MoneyText(amount = amount, style = MaterialTheme.typography.titleMedium, color = amountColor(role)) }
        },
    )
}

@Composable
private fun BaseAmountText(display: String) {
    Text(
        text = stringResource(R.string.transaction_base_amount, bidiIsolate(display)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun baseLine(display: String?): String? =
    display?.let { stringResource(R.string.transaction_base_amount, bidiIsolate(it)) }

/** Confirms the delete, naming the entry so the owner can see which one is about to go. */
@Composable
internal fun TransactionDeleteDialog(row: TransactionRow, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val formatDate = rememberDateFormatter()
    val target = stringResource(
        R.string.transaction_detail_delete_target,
        bidiIsolate(subjectOf(row)),
        bidiIsolate(signedAmount(row.type.amountRole(), row.amountDisplay)),
        bidiIsolate(formatDate(row.date)),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_transaction_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap),
            ) {
                Text(target, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.delete_transaction_body))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = WalletTheme.dimensions.minTouchTarget).testTag(DELETE_CONFIRM_TAG),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = WalletTheme.dimensions.minTouchTarget).testTag(DELETE_CANCEL_TAG),
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** What the entry is about: its category for the kinds that have one as their subject, else its kind. */
@Composable
private fun subjectOf(row: TransactionRow): String {
    val category = row.categoryLabel
    return if (row.type.hasCategorySubject() && category != null) category.text() else kindLabel(row.type)
}

/** The account line under the amount; the route for a transfer, isolated so its arrow cannot flip. */
@Composable
private fun accountLine(row: TransactionRow): String? {
    val missing = stringResource(R.string.value_missing)
    return when {
        row.type == TransactionRowType.TRANSFER -> stringResource(
            R.string.transfer_route,
            bidiIsolate(row.accountName ?: missing),
            bidiIsolate(row.destAccountName ?: missing),
        )
        row.type.movesAccount() -> row.accountName ?: missing
        else -> null
    }
}

/** A repayment's category is its interest's, not the entry's subject, so it is not listed here. */
private fun TransactionRowType.hasCategorySubject(): Boolean = when (this) {
    TransactionRowType.INCOME,
    TransactionRowType.EXPENSE,
    TransactionRowType.REFUND,
    TransactionRowType.CREDIT_PURCHASE,
    TransactionRowType.EARNED_UNPAID,
    TransactionRowType.WRITE_OFF_LOSS,
    TransactionRowType.WRITE_OFF_FORGIVEN,
    -> true
    TransactionRowType.TRANSFER,
    TransactionRowType.BORROW,
    TransactionRowType.LEND,
    TransactionRowType.OWED_BEFORE,
    TransactionRowType.REPAYMENT_PAID,
    TransactionRowType.REPAYMENT_RECEIVED,
    -> false
}

/** Kinds that move money in or out of an account; the rest only change what is owed or counted. */
private fun TransactionRowType.movesAccount(): Boolean = when (this) {
    TransactionRowType.INCOME,
    TransactionRowType.EXPENSE,
    TransactionRowType.REFUND,
    TransactionRowType.TRANSFER,
    TransactionRowType.BORROW,
    TransactionRowType.LEND,
    TransactionRowType.REPAYMENT_PAID,
    TransactionRowType.REPAYMENT_RECEIVED,
    -> true
    TransactionRowType.OWED_BEFORE,
    TransactionRowType.CREDIT_PURCHASE,
    TransactionRowType.EARNED_UNPAID,
    TransactionRowType.WRITE_OFF_LOSS,
    TransactionRowType.WRITE_OFF_FORGIVEN,
    -> false
}

/** How a debt-linked kind changes its debt, in words; null for kinds that touch no debt. */
@StringRes
private fun TransactionRowType.debtEffect(): Int? = when (this) {
    TransactionRowType.BORROW, TransactionRowType.CREDIT_PURCHASE -> R.string.transaction_detail_debt_adds_owed_by_me
    TransactionRowType.LEND, TransactionRowType.EARNED_UNPAID -> R.string.transaction_detail_debt_adds_owed_to_me
    TransactionRowType.OWED_BEFORE -> R.string.transaction_detail_debt_owed_before
    TransactionRowType.REPAYMENT_PAID -> R.string.transaction_detail_debt_reduces_owed_by_me
    TransactionRowType.REPAYMENT_RECEIVED -> R.string.transaction_detail_debt_reduces_owed_to_me
    TransactionRowType.WRITE_OFF_LOSS -> R.string.transaction_detail_debt_written_off_loss
    TransactionRowType.WRITE_OFF_FORGIVEN -> R.string.transaction_detail_debt_written_off_forgiven
    TransactionRowType.INCOME,
    TransactionRowType.EXPENSE,
    TransactionRowType.REFUND,
    TransactionRowType.TRANSFER,
    -> null
}
