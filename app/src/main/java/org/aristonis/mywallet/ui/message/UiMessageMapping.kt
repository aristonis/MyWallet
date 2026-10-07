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
    is WalletException.CategoryStructureInvalid -> UiMessage.CategoryStructureInvalid
    is WalletException.CategoryNotTopLevel -> UiMessage.CategoryNotTopLevel
    is WalletException.SystemCategoryProtected -> UiMessage.SystemCategoryProtected
    is WalletException.CurrencyMismatch -> UiMessage.CurrencyMismatch(amountCurrency, accountCurrency)
    is WalletException.CurrencyNotFound -> UiMessage.CurrencyNotFound(code)
    is WalletException.RateUnderflow -> UiMessage.RateUnderflow(code)
    is WalletException.MissingRate -> UiMessage.MissingRate(code)
    is WalletException.TransactionNotFound -> UiMessage.TransactionNotFound
    is WalletException.TransferCurrencyPairChanged -> UiMessage.TransferCurrencyPairChanged
    is WalletException.AmountRoundsToZero -> UiMessage.AmountRoundsToZero(currencyCode)
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
    else -> UiMessage.Unexpected
}

/**
 * A required choice the form never collected — no category on an expense, no destination on a
 * transfer. It carries what to tell the user rather than a sentence, so the same rejection reads
 * correctly wherever it surfaces.
 */
class MissingSelectionException(val uiMessage: UiMessage) :
    IllegalArgumentException(uiMessage::class.simpleName)
