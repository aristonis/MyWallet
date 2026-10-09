package org.aristonis.mywallet.ui.load

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * A screen's reads, started over each time the user asks to try again.
 *
 * A retry first shows the loading state, so the screen answers the tap even when the new read fails
 * the same way: a failed state equal to the one already on screen would not change anything a user
 * can see. Only a retry does this. The first read, and the reads that restart when a hidden screen
 * comes back into view, go straight to their data, so the screen does not flash a spinner over the
 * numbers it was showing.
 *
 * Each attempt replaces the one before it. A catch placed inside [read] ends only its own attempt,
 * so the next retry can still read. A retry tapped in the instant before the reads subscribe is not
 * seen; the screen then shows its failed state again and the next tap works.
 */
class ReadAttempts {

    // A retry is an event, not a value: replaying it to a returning screen would show loading for
    // no reason. One slot is enough because a newer request makes an older unread one pointless.
    private val retries = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Starts the reads over, showing the loading state until the new read answers. */
    fun retry() {
        retries.tryEmit(Unit)
    }

    /**
     * Runs [read] now and again on every [retry]. A retry's attempt emits [loading] before the read
     * answers; the first attempt does not, as the screen already starts out loading.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> reading(loading: () -> T, read: () -> Flow<T>): Flow<T> =
        retries
            .map { true }
            .onStart { emit(false) }
            .flatMapLatest { isRetry ->
                if (isRetry) read().onStart { emit(loading()) } else read()
            }
}
