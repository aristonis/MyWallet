package org.aristonis.mywallet.data.backup

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The single JSON gateway for wallet backups. Reads are strict — unknown keys and missing required
 * fields are rejected rather than silently defaulted — so a decoded [WalletBackup] is guaranteed
 * complete before restore touches it. [decode] deliberately lets SerializationException /
 * IllegalArgumentException propagate on corrupt or partial input; callers treat that as "reject".
 */
object BackupCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    fun encode(backup: WalletBackup): String = json.encodeToString(backup)

    fun decode(text: String): WalletBackup = json.decodeFromString(text)
}
