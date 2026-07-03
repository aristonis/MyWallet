package org.aristonis.mywallet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate

/**
 * The "what day is it?" seam. A function type (not a stored value) because the answer must be read
 * when a transaction is recorded, not once at injection time. ViewModels take this instead of calling
 * [LocalDate.now] directly, so a test can pin the date to a fixed day.
 */
fun interface TodayProvider {
    fun today(): LocalDate
}

@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    @Provides
    fun provideTodayProvider(): TodayProvider = TodayProvider { LocalDate.now() }
}
