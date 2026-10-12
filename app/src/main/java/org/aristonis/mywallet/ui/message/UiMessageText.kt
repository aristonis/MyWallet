package org.aristonis.mywallet.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.aristonis.mywallet.R
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.currentLocale

/**
 * The wording of a [UiMessage]. This is the only place a message becomes text, and the only layer
 * that is allowed to know about `R`.
 */
@Composable
fun UiMessage.text(): String = when (this) {
    UiMessage.AmountMissing -> stringResource(R.string.msg_amount_missing)
    UiMessage.AmountNotPositive -> stringResource(R.string.msg_amount_not_positive)
    UiMessage.AmountOutOfRange -> stringResource(R.string.msg_amount_out_of_range)
    UiMessage.RateMissing -> stringResource(R.string.msg_rate_missing)
    UiMessage.RateNotPositive -> stringResource(R.string.msg_rate_not_positive)
    UiMessage.RateOutOfRange -> stringResource(R.string.msg_rate_out_of_range)
    UiMessage.NotANumber -> stringResource(R.string.msg_not_a_number)

    UiMessage.AccountRequired -> stringResource(R.string.msg_account_required)
    UiMessage.CurrencyRequired -> stringResource(R.string.msg_currency_required)
    UiMessage.CategoryRequired -> stringResource(R.string.msg_category_required)
    UiMessage.DestinationAccountRequired -> stringResource(R.string.msg_destination_account_required)
    UiMessage.NameRequired -> stringResource(R.string.msg_name_required)

    UiMessage.AccountNotFound -> stringResource(R.string.msg_account_not_found)
    UiMessage.AccountInUse -> stringResource(R.string.msg_account_in_use)
    UiMessage.AccountCurrencyLocked -> stringResource(R.string.msg_account_currency_locked)
    UiMessage.AccountArchived -> stringResource(R.string.msg_account_archived)

    UiMessage.CategoryNotFound -> stringResource(R.string.msg_category_not_found)
    is UiMessage.DuplicateCategoryName -> stringResource(R.string.msg_duplicate_category_name, name)
    UiMessage.CategoryDepthExceeded -> stringResource(R.string.msg_category_depth_exceeded)
    UiMessage.CategoryKindMismatch -> stringResource(R.string.msg_category_kind_mismatch)
    UiMessage.CategoryWrongKind -> stringResource(R.string.msg_category_wrong_kind)
    UiMessage.CategoryStructureInvalid -> stringResource(R.string.msg_category_structure_invalid)
    UiMessage.CategoryNotTopLevel -> stringResource(R.string.msg_category_not_top_level)
    UiMessage.SystemCategoryProtected -> stringResource(R.string.msg_system_category_protected)

    is UiMessage.CurrencyMismatch ->
        stringResource(R.string.msg_currency_mismatch, amountCurrency, accountCurrency)
    is UiMessage.CurrencyNotFound -> stringResource(R.string.msg_currency_not_found, code)
    is UiMessage.MissingRate -> stringResource(R.string.msg_missing_rate, code)
    is UiMessage.RateUnderflow -> stringResource(R.string.msg_rate_underflow, code)
    is UiMessage.AmountRoundsToZero -> stringResource(R.string.msg_amount_rounds_to_zero, currencyCode)

    UiMessage.DeductionOutOfRange -> stringResource(R.string.msg_deduction_out_of_range)
    UiMessage.DeductionValueNotPositive -> stringResource(R.string.msg_deduction_value_not_positive)
    UiMessage.DeductionsExceedIncome -> stringResource(R.string.msg_deductions_exceed_income)
    UiMessage.DeductionOverrideCurrencyMismatch -> stringResource(R.string.msg_deduction_override_currency_mismatch)
    UiMessage.DeductionItemInUse -> stringResource(R.string.msg_deduction_item_in_use)
    UiMessage.DeductionItemNotFound -> stringResource(R.string.msg_deduction_item_not_found)
    UiMessage.DeductionItemNameTaken -> stringResource(R.string.msg_deduction_item_name_taken)
    UiMessage.DeductionSnapshotInvalid -> stringResource(R.string.msg_deduction_snapshot_invalid)
    UiMessage.FeeNotBelowAmount -> stringResource(R.string.msg_fee_not_below_amount)
    UiMessage.FeeValueNegative -> stringResource(R.string.msg_fee_value_negative)
    UiMessage.FeePercentAboveHundred -> stringResource(R.string.msg_fee_percent_above_hundred)
    is UiMessage.FeeTooManyDecimals -> if (decimalPlaces == 0) {
        stringResource(R.string.msg_fee_whole_number, currencyCode)
    } else {
        pluralStringResource(R.plurals.msg_fee_too_many_decimals, decimalPlaces, currencyCode, decimalPlaces)
    }

    UiMessage.NameBlank -> stringResource(R.string.msg_name_blank)
    UiMessage.DebtNotFound -> stringResource(R.string.msg_debt_not_found)
    UiMessage.DebtSideMismatch -> stringResource(R.string.msg_debt_side_mismatch)
    is UiMessage.RepaymentExceedsRemaining ->
        stringResource(R.string.msg_repayment_exceeds_remaining, displayAsSummed(remaining))
    is UiMessage.DebtHasNoRemaining ->
        stringResource(R.string.msg_debt_has_no_remaining, bidiIsolate(counterparty))
    is UiMessage.DebtBelowZero -> {
        val (name, formattedAmount) = debtBelowZeroArgs(counterparty, displayAsSummed(amount))
        stringResource(R.string.msg_debt_below_zero, name, formattedAmount)
    }
    // Isolated for the same reason as in the below-zero message: the name can run the other way.
    is UiMessage.DebtNameTaken -> stringResource(R.string.msg_debt_name_taken, bidiIsolate(counterparty))

    UiMessage.TransactionNotFound -> stringResource(R.string.msg_transaction_not_found)
    UiMessage.EntryAlreadyRestored -> stringResource(R.string.msg_entry_already_restored)
    UiMessage.DataUnreadable -> stringResource(R.string.msg_data_unreadable)
    UiMessage.TransferCurrencyPairChanged -> stringResource(R.string.msg_transfer_currency_pair_changed)
    UiMessage.TransactionRestoreFailed -> stringResource(R.string.msg_transaction_restore_failed)

    UiMessage.BackupSaved -> stringResource(R.string.msg_backup_saved)
    UiMessage.BackupSaveFailed -> stringResource(R.string.msg_backup_save_failed)
    UiMessage.BackupFileUnreadable -> stringResource(R.string.msg_backup_file_unreadable)
    UiMessage.BackupRestored -> stringResource(R.string.msg_backup_restored)
    UiMessage.BackupNotRecognized -> stringResource(R.string.msg_backup_not_recognized)
    UiMessage.BackupFromNewerVersion -> stringResource(R.string.msg_backup_from_newer_version)
    UiMessage.BackupRestoreFailed -> stringResource(R.string.msg_backup_restore_failed)

    UiMessage.Unexpected -> stringResource(R.string.msg_unexpected)
}

/**
 * [money] with the device locale's separators and its ISO code, through the same [MoneyFormatter] the
 * screens use. A message carries no currency list, so the digits shown are the ones the amount
 * already has: it is a sum of stored amounts, which keep the scale they were entered with, and
 * showing that scale rounds nothing away.
 */
@Composable
private fun displayAsSummed(money: Money): String {
    val decimalPlaces = money.amount.scale().coerceAtLeast(0)
    return MoneyFormatter(currentLocale()).format(money.amount, decimalPlaces, money.currencyCode)
}

/**
 * The two values the below-zero message substitutes. The typed name is isolated because it can run
 * in the other direction from the sentence: an Arabic name in an English sentence, or the reverse,
 * would otherwise pull the amount next to it onto the wrong side.
 */
internal fun debtBelowZeroArgs(counterparty: String, formattedAmount: String): Pair<String, String> =
    Pair(bidiIsolate(counterparty), formattedAmount)
