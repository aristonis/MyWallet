package org.aristonis.mywallet.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.aristonis.mywallet.ui.settings.AndroidDocumentIo
import org.aristonis.mywallet.ui.settings.DocumentIo
import javax.inject.Singleton

/** Binds the Storage Access Framework boundary to its Android implementation. */
@Module
@InstallIn(SingletonComponent::class)
object IoModule {

    @Provides @Singleton
    fun provideDocumentIo(@ApplicationContext context: Context): DocumentIo = AndroidDocumentIo(context)
}
