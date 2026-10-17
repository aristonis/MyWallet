package org.aristonis.mywallet.ui.message

import kotlinx.coroutines.CancellationException
import org.aristonis.mywallet.data.format.MoneyParseError
import org.aristonis.mywallet.data.format.MoneyParseException
import org.aristonis.mywallet.domain.error.WalletException

/**
 * Turns a failure into the thing the user should be told about it.
 *
 * The domain and data layers name their faults but cannot word them; this is the one place that
 * decides which named fault a screen shows, and the last point before anything reaches a person.
 */
fun WalletException.toUiMessage(): UiMessage = when (this) {
    is WalletException.AccountNotFound -> UiMessage.AccountNotFound
    is WalletException.AccountInUse -> UiMessage.AccountInUse
    is WalletException.AccountCurrencyLocked -> UiMessage.AccountCurrencyLocked
    is WalletException.AccountArchived -> UiMessage.AccountArchived
    is WalletException.CategoryNotFound -> UiMessage.CategoryNotFound
    is WalletException.DuplicateCategoryName -> UiMessage.DuplicateCategoryName(name)
    is WalletException.CategoryDepthExceeded -> UiMessage.CategoryDepthExceeded
    is WalletException.CategoryKindMismatch -> UiMessage.CategoryKindMismatch
    is WalletException.CategoryWrongKind -> UiMessage.CategoryWrongKind
    is WalletException.CategoryStructureInvalid -> UiMessage.CategoryStructureInvalid
    is WalletException.CategoryNotTopLevel -> UiMessage.CategoryNotTopLevel
    is WalletException.SystemCategoryProtected -> UiMessage.SystemCategoryProtected
    is WalletException.CurrencyMismatch -> UiMessage.CurrencyMismatch(amountCurrency, accountCurrency)
    is WalletException.CurrencyNotFound -> UiMessage.CurrencyNotFound(code)
    is WalletException.RateUnderflow -> UiMessage.RateUnderflow(code)
    is WalletException.MissingRate -> UiMessage.MissingRate(code)
    is WalletException.TransactionNotFound -> UiMessage.TransactionNotFound
    is WalletException.EntryIdTaken -> UiMessage.EntryAlreadyRestored
    is WalletException.UnreadableRow -> UiMessage.DataUnreadable
    is WalletException.TransferCurrencyPairChanged -> UiMessage.TransferCurrencyPairChanged
    is WalletException.AmountRoundsToZero -> UiMessage.AmountRoundsToZero(currencyCode)
    is WalletException.DeductionOutOfRange -> UiMessage.DeductionOutOfRange
    is WalletException.DeductionValueNotPositive -> UiMessage.DeductionValueNotPositive
    is WalletException.DeductionsExceedIncome -> UiMessage.DeductionsExceedIncome
    is WalletException.FeeAndDeductionsExceedIncome -> UiMessage.FeeAndDeductionsExceedIncome
    is WalletException.DeductionOverrideCurrencyMismatch -> UiMessage.DeductionOverrideCurrencyMismatch
    is WalletException.DeductionItemInUse -> UiMessage.DeductionItemInUse
    is WalletException.DeductionItemNotFound -> UiMessage.DeductionItemNotFound
    is WalletException.DeductionItemNameTaken -> UiMessage.DeductionItemNameTaken
    is WalletException.DeductionSnapshotInvalid -> UiMessage.DeductionSnapshotInvalid
    is WalletException.FeeNotBelowAmount -> UiMessage.FeeNotBelowAmount
    is WalletException.FeeValueNegative -> UiMessage.FeeValueNegative
    is WalletException.FeePercentAboveHundred -> UiMessage.FeePercentAboveHundred
    is WalletException.PaidNowNotBelowAmount -> UiMessage.PaidNowNotBelowAmount
    is WalletException.PaidLaterFeeWithoutPaidNow -> UiMessage.PaidLaterFeeWithoutPaidNow
    is WalletException.NameBlank -> UiMessage.NameBlank
    is WalletException.DebtNotFound -> UiMessage.DebtNotFound
    is WalletException.DebtSideMismatch -> UiMessage.DebtSideMismatch
    is WalletException.RepaymentExceedsRemaining -> UiMessage.RepaymentExceedsRemaining(remaining)
    is WalletException.DebtHasNoRemaining -> UiMessage.DebtHasNoRemaining(counterparty)
    is WalletException.DebtBelowZero -> UiMessage.DebtBelowZero(counterparty, amount)
    is WalletException.DebtNameTaken -> UiMessage.DebtNameTaken(counterparty)
    is WalletException.BackupInvalid -> UiMessage.BackupNotRecognized
    is WalletException.BackupVersionUnsupported -> UiMessage.BackupFromNewerVersion
}

fun MoneyParseError.toUiMessage(): UiMessage = when (this) {
    MoneyParseError.AMOUNT_MISSING -> UiMessage.AmountMissing
    MoneyParseError.AMOUNT_NOT_POSITIVE -> UiMessage.AmountNotPositive
    MoneyParseError.AMOUNT_OUT_OF_RANGE -> UiMessage.AmountOutOfRange
    MoneyParseError.RATE_MISSING -> UiMessage.RateMissing
    MoneyParseError.RATE_NOT_POSITIVE -> UiMessage.RateNotPositive
    MoneyParseError.RATE_OUT_OF_RANGE -> UiMessage.RateOutOfRange
    MoneyParseError.NOT_A_NUMBER -> UiMessage.NotANumber
}

/**
 * The boundary every `catch` goes through.
 *
 * Anything without a named cause becomes [UiMessage.Unexpected] rather than its own `message`: those
 * strings are written for whoever reads the stack trace, are only ever in English, and sometimes
 * carry a row id. Cancellation is deliberately not handled here — it is control flow, not a failure,
 * and callers must let it propagate.
 */
fun Throwable.toUiMessage(): UiMessage = when (this) {
    is CancellationException -> throw this
    is WalletException -> toUiMessage()
    is MoneyParseException -> error.toUiMessage()
    is MissingSelectionException -> uiMessage
    is RefusedInputException -> uiMessage
    else -> UiMessage.Unexpected
}

/**
 * A required choice the form never collected — no category on an expense, no destination on a
 * transfer. It carries what to tell the user rather than a sentence, so the same rejection reads
 * correctly wherever it surfaces.
 */
class MissingSelectionException(val uiMessage: UiMessage) :
    IllegalArgumentException(uiMessage::class.simpleName)

/**
 * A typed value the form refuses before any use case sees it, for a rule only the form knows (a fee
 * percentage above 100). Like [MissingSelectionException] it carries what to tell the user.
 */
class RefusedInputException(val uiMessage: UiMessage) :
    IllegalArgumentException(uiMessage::class.simpleName)
