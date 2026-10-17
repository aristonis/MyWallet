package org.aristonis.mywallet.ui.message

import org.aristonis.mywallet.domain.model.Money

/**
 * Something the app needs to tell the user, named rather than worded.
 *
 * View-models put one of these in their state; only a composable turns it into text. That split is
 * what keeps every translatable string in `res/values/strings.xml` while leaving the view-models
 * free of `Context` and of resource ids — and it is what makes a test able to assert *which* thing
 * went wrong instead of matching an English sentence that a translator may later change.
 *
 * The values a case carries are the ones the sentence needs (a currency code, a name), never a
 * pre-built phrase.
 */
sealed interface UiMessage {

    // -- Typed input ------------------------------------------------------------------------------

    data object AmountMissing : UiMessage
    data object AmountNotPositive : UiMessage
    data object AmountOutOfRange : UiMessage
    data object RateMissing : UiMessage
    data object RateNotPositive : UiMessage
    data object RateOutOfRange : UiMessage
    data object NotANumber : UiMessage

    // -- Incomplete form --------------------------------------------------------------------------

    data object AccountRequired : UiMessage
    data object CurrencyRequired : UiMessage
    data object CategoryRequired : UiMessage
    data object DestinationAccountRequired : UiMessage
    data object NameRequired : UiMessage

    // -- Accounts ---------------------------------------------------------------------------------

    data object AccountNotFound : UiMessage
    data object AccountInUse : UiMessage
    data object AccountCurrencyLocked : UiMessage
    data object AccountArchived : UiMessage

    // -- Categories -------------------------------------------------------------------------------

    data object CategoryNotFound : UiMessage
    data class DuplicateCategoryName(val name: String) : UiMessage
    data object CategoryDepthExceeded : UiMessage
    data object CategoryKindMismatch : UiMessage
    data object CategoryWrongKind : UiMessage
    data object CategoryStructureInvalid : UiMessage
    data object CategoryNotTopLevel : UiMessage
    data object SystemCategoryProtected : UiMessage

    // -- Money and currencies ---------------------------------------------------------------------

    data class CurrencyMismatch(val amountCurrency: String, val accountCurrency: String) : UiMessage
    data class CurrencyNotFound(val code: String) : UiMessage
    data class MissingRate(val code: String) : UiMessage
    data class RateUnderflow(val code: String) : UiMessage
    data class AmountRoundsToZero(val currencyCode: String) : UiMessage

    // -- Deductions ------------------------------------------------------------------------------

    data object DeductionOutOfRange : UiMessage
    data object DeductionValueNotPositive : UiMessage
    data object DeductionsExceedIncome : UiMessage
    /** The combined fee and deductions must not exceed the gross income. */
    data object FeeAndDeductionsExceedIncome : UiMessage
    data object DeductionOverrideCurrencyMismatch : UiMessage
    data object DeductionItemInUse : UiMessage
    data object DeductionItemNotFound : UiMessage
    data object DeductionItemNameTaken : UiMessage
    data object DeductionSnapshotInvalid : UiMessage
    data object FeeNotBelowAmount : UiMessage
    data object FeeValueNegative : UiMessage
    data object FeePercentAboveHundred : UiMessage
    /** A fixed fee typed with more decimals than [currencyCode] has; it is refused, never rounded. */
    data class FeeTooManyDecimals(val currencyCode: String, val decimalPlaces: Int) : UiMessage

    // -- Debts ------------------------------------------------------------------------------------

    data object NameBlank : UiMessage
    data object DebtNotFound : UiMessage
    data object DebtSideMismatch : UiMessage
    data class RepaymentExceedsRemaining(val remaining: Money) : UiMessage
    data class DebtHasNoRemaining(val counterparty: String) : UiMessage

    /** [amount] is how far below zero the debt with [counterparty] would go, as a positive value. */
    data class DebtBelowZero(val counterparty: String, val amount: Money) : UiMessage

    /** Another debt on the same side and currency already answers to [counterparty]. */
    data class DebtNameTaken(val counterparty: String) : UiMessage

    // -- Transactions -----------------------------------------------------------------------------

    data object TransactionNotFound : UiMessage
    data object EntryAlreadyRestored : UiMessage
    data object TransferCurrencyPairChanged : UiMessage
    data object TransactionRestoreFailed : UiMessage

    /** Paid later requires a paid-now amount below the full expense amount. */
    data object PaidNowNotBelowAmount : UiMessage

    /** A fee on a paid-later expense requires a positive amount paid now. */
    data object PaidLaterFeeWithoutPaidNow : UiMessage

    /** A stored entry this build cannot read; its id and kind stay in the log, never on screen. */
    data object DataUnreadable : UiMessage

    // -- Backup and restore -----------------------------------------------------------------------

    data object BackupSaved : UiMessage
    data object BackupSaveFailed : UiMessage

    /** The wallet holds data a restore would refuse, so no backup was written until it is fixed. */
    data object BackupWalletNeedsFixing : UiMessage
    data object BackupFileUnreadable : UiMessage
    data object BackupRestored : UiMessage
    data object BackupNotRecognized : UiMessage
    data object BackupFromNewerVersion : UiMessage
    data object BackupRestoreFailed : UiMessage

    // -- Tracking export -------------------------------------------------------------------------

    data object ExportInterrupted : UiMessage

    /**
     * A failure with no specific handling. It exists so an unmapped exception still reaches the user
     * as a plain apology instead of an English developer message from somewhere deep in the stack.
     */
    data object Unexpected : UiMessage
}
