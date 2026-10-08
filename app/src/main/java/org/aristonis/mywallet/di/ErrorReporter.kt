package org.aristonis.mywallet.di

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Where a failure goes when a screen turns it into a message instead of a crash. The user sees a
 * plain sentence; the cause still has to survive for whoever diagnoses the report, so it is never
 * just dropped. A seam rather than a direct log call so tests can check a failure was reported.
 */
fun interface ErrorReporter {
    fun report(message: String, error: Throwable)
}

@Module
@InstallIn(SingletonComponent::class)
object ErrorReporterModule {

    private const val TAG = "MyWallet"

    @Provides
    fun provideErrorReporter(): ErrorReporter = ErrorReporter { message, error -> Log.w(TAG, message, error) }
}
