package org.aristonis.mywallet.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.aristonis.mywallet.R

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
    UiMessage.CategoryStructureInvalid -> stringResource(R.string.msg_category_structure_invalid)
    UiMessage.CategoryNotTopLevel -> stringResource(R.string.msg_category_not_top_level)
    UiMessage.SystemCategoryProtected -> stringResource(R.string.msg_system_category_protected)

    is UiMessage.CurrencyMismatch ->
        stringResource(R.string.msg_currency_mismatch, amountCurrency, accountCurrency)
    is UiMessage.CurrencyNotFound -> stringResource(R.string.msg_currency_not_found, code)
    is UiMessage.MissingRate -> stringResource(R.string.msg_missing_rate, code)
    is UiMessage.RateUnderflow -> stringResource(R.string.msg_rate_underflow, code)
    is UiMessage.AmountRoundsToZero -> stringResource(R.string.msg_amount_rounds_to_zero, currencyCode)

    UiMessage.TransactionNotFound -> stringResource(R.string.msg_transaction_not_found)
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
