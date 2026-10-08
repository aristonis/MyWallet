package org.aristonis.mywallet

import org.aristonis.mywallet.di.ErrorReporter

/** Keeps every reported failure, so a test can prove a failure was reported rather than swallowed. */
class RecordingErrorReporter : ErrorReporter {
    val reported = mutableListOf<Throwable>()

    override fun report(message: String, error: Throwable) {
        reported += error
    }
}
