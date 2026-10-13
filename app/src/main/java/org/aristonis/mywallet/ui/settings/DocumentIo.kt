package org.aristonis.mywallet.ui.settings

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** SAF boundary owning stream lifetimes; serialization runs directly on the chosen document. */
interface DocumentIo {
    /** Truncates the document before [block] runs, so anything that can refuse the write must run first. */
    suspend fun write(uri: Uri, block: suspend (OutputStream) -> Unit)
    suspend fun read(uri: Uri, block: suspend (InputStream) -> Unit)
}

/** Opens and closes document streams off the main thread; unavailable documents fail explicitly. */
class AndroidDocumentIo(
    @ApplicationContext private val context: Context,
) : DocumentIo {
    override suspend fun write(uri: Uri, block: suspend (OutputStream) -> Unit): Unit = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Could not open document for writing")
        stream.use { block(it) }
    }

    override suspend fun read(uri: Uri, block: suspend (InputStream) -> Unit): Unit = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open document for reading")
        stream.use { block(it) }
    }
}
