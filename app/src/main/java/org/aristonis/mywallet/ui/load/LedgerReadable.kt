package org.aristonis.mywallet.ui.load

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.ui.message.UiMessage
import kotlin.coroutines.cancellation.CancellationException

/**
 * The ledger's health as a value for a screen that combines it with its own reads: true while every
 * stored entry can be read, false from the moment one cannot.
 *
 * It emits only when the answer changes. The health read answers again on every write, and a
 * screen that rebuilt its rows for each of those answers would do the work twice per save.
 *
 * Only an unreadable row becomes false; it is reported to [errors] once and ends this read. Kept as
 * a value, it stays the last word in the screen's combine, so choosing other dates cannot hide it,
 * and the screen's Retry starts a fresh read. Any other failure (a disk error, say) is not a row
 * that cannot be read and is passed on unchanged, for the screen's own failure handling.
 *
 * @param screen Names the screen in the report.
 */
fun ObserveLedgerHealth.readable(errors: ErrorReporter, screen: String): Flow<Boolean> =
    invoke()
        .map { true }
        .catch { error ->
            if (error !is WalletException.UnreadableRow) throw error
            errors.report("$screen found stored data it cannot read", error)
            emit(false)
        }
        .distinctUntilChanged()

/**
 * The ledger's health as the failure a screen shows: null while every entry can be read,
 * [UiMessage.DataUnreadable] once one cannot, and [UiMessage.Unexpected] when the health read itself
 * fails for another reason. Every failure is reported once; none escapes to close the app.
 */
fun ObserveLedgerHealth.loadFailure(errors: ErrorReporter, screen: String): Flow<UiMessage?> =
    readable(errors, screen)
        .map<Boolean, UiMessage?> { isReadable -> if (isReadable) null else UiMessage.DataUnreadable }
        .catch { error ->
            if (error is CancellationException) throw error
            errors.report("$screen could not check the stored data", error)
            emit(UiMessage.Unexpected)
        }

/**
 * What a failed read tells the user: a row the app cannot read gets its own wording, telling them
 * their data is safe and to update; anything else is the generic failure.
 */
fun Throwable.toLoadFailure(): UiMessage = when (this) {
    is CancellationException -> throw this
    is WalletException.UnreadableRow -> UiMessage.DataUnreadable
    else -> UiMessage.Unexpected
}
