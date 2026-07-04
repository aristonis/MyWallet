package org.aristonis.mywallet.ui.settings

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The Storage Access Framework boundary: reads/writes the text a backup is (de)serialized to at a
 * user-picked [Uri]. Keeping [ContentResolver]/[Uri] behind this interface keeps them out of the
 * domain and out of the view-model's unit-tested logic — the view-model only ever sees plain text.
 */
interface DocumentIo {
    suspend fun writeText(uri: Uri, text: String)
    suspend fun readText(uri: Uri): String
}

/**
 * Android-backed [DocumentIo]. Streams are opened off the main thread and a null stream fails loud
 * (the resolver couldn't grant access), never a silent no-op that would look like a successful
 * backup while writing nothing.
 */
class AndroidDocumentIo(
    @ApplicationContext private val context: Context,
) : DocumentIo {

    override suspend fun writeText(uri: Uri, text: String): Unit = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Could not open $uri for writing")
        stream.use { it.write(text.toByteArray()) }
    }

    override suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open $uri for reading")
        stream.use { it.readBytes().decodeToString() }
    }
}
