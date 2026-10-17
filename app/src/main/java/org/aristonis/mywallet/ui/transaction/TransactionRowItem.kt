package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.WalletListRow
import org.aristonis.mywallet.ui.format.AmountRole
import org.aristonis.mywallet.ui.format.amountRole
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.signedAmount
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.text
import org.aristonis.mywallet.ui.theme.WalletTheme
import org.aristonis.mywallet.ui.theme.amountColor

private const val AMOUNT_PILL_BACKGROUND_ALPHA = 0.12f

/** One row of the transactions list. Amounts arrive formatted; the row adds the sign its kind carries. */
@Composable
internal fun TransactionRowItem(row: TransactionRow, onClick: () -> Unit) {
    val role = row.type.amountRole()
    if (row.type == TransactionRowType.TRANSFER) {
        BoxWithConstraints {
            if (maxWidth / LocalDensity.current.fontScale <= 400.dp) {
                CompactTransferRow(row = row, onClick = onClick)
            } else {
                StandardTransactionRow(row = row, role = role, onClick = onClick)
            }
        }
        return
    }
    StandardTransactionRow(row = row, role = role, onClick = onClick)
}

@Composable
private fun StandardTransactionRow(row: TransactionRow, role: AmountRole, onClick: () -> Unit) {
    WalletListRow(
        headline = headline(row),
        supporting = supporting(row),
        leadingIcon = row.type.icon(),
        leadingIconDescription = kindLabel(row.type),
        leadingIconTint = amountColor(role),
        trailing = { TrailingAmounts(row, role) },
        onClick = onClick,
        supportingContent = if (row.type == TransactionRowType.INCOME) {
            { AmountDetails(row, role) }
        } else {
            null
        },
    )
}

/** At phone width, the complete transfer route and both currency amounts need separate lines. */
@Composable
private fun CompactTransferRow(row: TransactionRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = WalletIcons.Transfer,
                contentDescription = kindLabel(row.type),
                tint = amountColor(AmountRole.NEUTRAL),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(headline(row), style = MaterialTheme.typography.titleMedium)
                supporting(row)?.let { context ->
                    Text(
                        context,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            TrailingAmounts(row, AmountRole.NEUTRAL)
        }
    }
}

/** Income keeps only its movement here so receipt details cannot squeeze the title. */
@Composable
private fun TrailingAmounts(row: TransactionRow, role: AmountRole) {
    Column(horizontalAlignment = Alignment.End) {
        val roleColor = amountColor(role)
        MoneyText(
            amount = amountLine(row),
            modifier = Modifier
                .background(roleColor.copy(alpha = AMOUNT_PILL_BACKGROUND_ALPHA), CircleShape)
                .padding(
                    horizontal = WalletTheme.dimensions.relatedGap,
                    vertical = WalletTheme.dimensions.grid,
                ),
            style = MaterialTheme.typography.titleMedium,
            color = roleColor,
        )
        if (row.type != TransactionRowType.INCOME) AmountDetails(row, role)
    }
}

/** Receipt, conversions and deductions retain their separate accounting meanings. */
@Composable
private fun AmountDetails(row: TransactionRow, role: AmountRole) {
    Column(horizontalAlignment = if (row.type == TransactionRowType.INCOME) Alignment.Start else Alignment.End) {
        val hasDeductionDetails = row.type == TransactionRowType.INCOME && row.deductions.isNotEmpty()
        if (hasDeductionDetails) IncomeReceiptDetails(row)
        if (role != AmountRole.NEUTRAL && !hasDeductionDetails) BaseAmount(row.countedBaseAmountDisplay)
        if (role == AmountRole.NEUTRAL) {
            row.countedAmountDisplay?.let { interest ->
                DetailAmount(
                    stringResource(R.string.transaction_interest, bidiIsolate(interest)),
                    row.countedAmountRole ?: AmountRole.NEUTRAL,
                )
                BaseAmount(row.countedBaseAmountDisplay)
            }
        }
        DeductionDetails(row)
        row.feeDisplay?.let { fee ->
            DetailAmount(stringResource(R.string.transaction_fee, bidiIsolate(fee)), AmountRole.EXPENSE)
            BaseAmount(row.feeBaseAmountDisplay)
        }
    }
}

@Composable
private fun IncomeReceiptDetails(row: TransactionRow) {
    Text(
        text = stringResource(R.string.income_summary_receives),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    row.grossAmountDisplay?.let { gross ->
        DetailAmount(
            stringResource(
                R.string.transaction_headline_with_note,
                stringResource(R.string.income_summary_gross),
                bidiIsolate(gross),
            ),
            AmountRole.INCOME,
        )
    }
    row.countedBaseAmountDisplay?.let { base ->
        Text(
            text = stringResource(
                R.string.transaction_headline_with_note,
                stringResource(R.string.income_summary_gross),
                stringResource(R.string.transaction_base_amount, bidiIsolate(base)),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeductionDetails(row: TransactionRow) {
    row.deductionSummaries.forEach { summary ->
        val timing = if (summary.timing == DeductionTiming.NOW) {
            R.string.income_deduction_now
        } else {
            R.string.income_deduction_later
        }
        DetailAmount(
            stringResource(
                R.string.transaction_deduction_summary,
                stringResource(timing),
                bidiIsolate(summary.amountDisplay),
                pluralStringResource(
                    R.plurals.transaction_saved_deduction_count,
                    summary.snapshotCount,
                    summary.snapshotCount,
                ),
            ),
            AmountRole.EXPENSE,
        )
    }
}

@Composable
private fun DetailAmount(text: String, role: AmountRole) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = amountColor(role))
}

@Composable
private fun BaseAmount(display: String?) {
    if (display == null) return
    Text(
        text = stringResource(R.string.transaction_base_amount, bidiIsolate(display)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What the row is about: the category for a kind that has one, the route for a transfer, and the
 * kind itself for the debt kinds without a category (a row holds no counterparty name).
 */
@Composable
private fun headline(row: TransactionRow): String {
    val missing = stringResource(R.string.value_missing)
    val subject = when (row.type) {
        TransactionRowType.TRANSFER ->
            // Two names with an arrow between them: without isolation a right-to-left layout can swap
            // which account the arrow points at, turning a transfer into its opposite.
            return stringResource(
                R.string.transfer_route,
                bidiIsolate(row.accountName ?: missing),
                bidiIsolate(row.destAccountName ?: missing),
            )
        TransactionRowType.INCOME,
        TransactionRowType.EXPENSE,
        TransactionRowType.REFUND,
        TransactionRowType.CREDIT_PURCHASE,
        TransactionRowType.EARNED_UNPAID,
        TransactionRowType.WRITE_OFF_LOSS,
        TransactionRowType.WRITE_OFF_FORGIVEN,
        -> row.categoryLabel?.text() ?: missing
        TransactionRowType.BORROW,
        TransactionRowType.LEND,
        TransactionRowType.OWED_BEFORE,
        TransactionRowType.REPAYMENT_PAID,
        TransactionRowType.REPAYMENT_RECEIVED,
        -> kindLabel(row.type)
    }
    // The note is what tells two otherwise identical rows apart, so it belongs on the first line.
    return row.note?.let { stringResource(R.string.transaction_headline_with_note, subject, it) } ?: subject
}

/**
 * The quieter second line names the kind and account for ordinary movements, and the account or
 * kind alone for entries whose headline already carries that context.
 */
@Composable
private fun supporting(row: TransactionRow): String? = when (row.type) {
    TransactionRowType.INCOME,
    TransactionRowType.EXPENSE,
    TransactionRowType.REFUND,
    -> stringResource(
        R.string.transaction_headline_with_note,
        kindLabel(row.type),
        bidiIsolate(row.accountName ?: stringResource(R.string.value_missing)),
    )
    TransactionRowType.BORROW,
    TransactionRowType.LEND,
    TransactionRowType.REPAYMENT_PAID,
    TransactionRowType.REPAYMENT_RECEIVED,
    -> row.accountName ?: stringResource(R.string.value_missing)
    TransactionRowType.TRANSFER -> row.note
    TransactionRowType.CREDIT_PURCHASE,
    TransactionRowType.EARNED_UNPAID,
    TransactionRowType.WRITE_OFF_LOSS,
    TransactionRowType.WRITE_OFF_FORGIVEN,
    -> kindLabel(row.type)
    TransactionRowType.OWED_BEFORE -> null
}

private fun TransactionRowType.icon(): ImageVector = when (this) {
    TransactionRowType.INCOME -> WalletIcons.Income
    TransactionRowType.EXPENSE -> WalletIcons.Expense
    TransactionRowType.REFUND -> WalletIcons.Income
    TransactionRowType.TRANSFER -> WalletIcons.Transfer
    TransactionRowType.BORROW -> WalletIcons.Borrow
    TransactionRowType.LEND -> WalletIcons.Lend
    TransactionRowType.OWED_BEFORE -> WalletIcons.OwedBefore
    TransactionRowType.CREDIT_PURCHASE -> WalletIcons.CreditPurchase
    TransactionRowType.EARNED_UNPAID -> WalletIcons.EarnedUnpaid
    // Both sides share their kind's glyph: the label tells them apart, and for a write-off the colour too.
    TransactionRowType.REPAYMENT_PAID, TransactionRowType.REPAYMENT_RECEIVED -> WalletIcons.Repayment
    TransactionRowType.WRITE_OFF_LOSS, TransactionRowType.WRITE_OFF_FORGIVEN -> WalletIcons.WriteOff
}

/** The name of the kind. The icon carries it too, so it is announced rather than left as decoration. */
@Composable
internal fun kindLabel(type: TransactionRowType): String = stringResource(
    when (type) {
        TransactionRowType.INCOME -> R.string.cd_income
        TransactionRowType.EXPENSE -> R.string.cd_expense
        TransactionRowType.REFUND -> R.string.transaction_type_refund
        TransactionRowType.TRANSFER -> R.string.cd_transfer
        TransactionRowType.BORROW -> R.string.transaction_type_borrow
        TransactionRowType.LEND -> R.string.transaction_type_lend
        TransactionRowType.OWED_BEFORE -> R.string.transaction_type_owed_before
        TransactionRowType.CREDIT_PURCHASE -> R.string.transaction_type_credit_purchase
        TransactionRowType.EARNED_UNPAID -> R.string.transaction_type_earned_unpaid
        TransactionRowType.REPAYMENT_PAID -> R.string.transaction_type_repayment_paid
        TransactionRowType.REPAYMENT_RECEIVED -> R.string.transaction_type_repayment_received
        TransactionRowType.WRITE_OFF_LOSS -> R.string.transaction_type_write_off_loss
        TransactionRowType.WRITE_OFF_FORGIVEN -> R.string.transaction_type_write_off_forgiven
    },
)

@Composable
private fun amountLine(row: TransactionRow): String {
    // A same-currency transfer moves one figure between two accounts, so showing it twice would say
    // nothing. A cross-currency one genuinely has two amounts, and hiding either would misstate it.
    val dest = row.destAmountDisplay
    if (row.type == TransactionRowType.TRANSFER && dest != null && dest != row.amountDisplay) {
        return stringResource(R.string.transfer_amounts, bidiIsolate(row.amountDisplay), bidiIsolate(dest))
    }
    return signedAmount(row.type.amountRole(), row.accountMovementDisplay ?: row.amountDisplay)
}
