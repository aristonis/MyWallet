package org.aristonis.mywallet.ui.message

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
    data object CategoryStructureInvalid : UiMessage
    data object CategoryNotTopLevel : UiMessage
    data object SystemCategoryProtected : UiMessage

    // -- Money and currencies ---------------------------------------------------------------------

    data class CurrencyMismatch(val amountCurrency: String, val accountCurrency: String) : UiMessage
    data class CurrencyNotFound(val code: String) : UiMessage
    data class MissingRate(val code: String) : UiMessage
    data class RateUnderflow(val code: String) : UiMessage
    data class AmountRoundsToZero(val currencyCode: String) : UiMessage

    // -- Transactions -----------------------------------------------------------------------------

    data object TransactionNotFound : UiMessage
    data object TransferCurrencyPairChanged : UiMessage
    data object TransactionRestoreFailed : UiMessage

    // -- Backup and restore -----------------------------------------------------------------------

    data object BackupSaved : UiMessage
    data object BackupSaveFailed : UiMessage
    data object BackupFileUnreadable : UiMessage
    data object BackupRestored : UiMessage
    data object BackupNotRecognized : UiMessage
    data object BackupFromNewerVersion : UiMessage
    data object BackupRestoreFailed : UiMessage

    /**
     * A failure with no specific handling. It exists so an unmapped exception still reaches the user
     * as a plain apology instead of an English developer message from somewhere deep in the stack.
     */
    data object Unexpected : UiMessage
}
