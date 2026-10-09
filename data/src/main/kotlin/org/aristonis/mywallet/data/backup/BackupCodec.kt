package org.aristonis.mywallet.data.backup

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The single JSON gateway for wallet backups. Reads are strict — unknown keys and missing required
 * fields are rejected rather than silently defaulted — so a decoded backup is guaranteed complete
 * before restore touches it. [decode] deliberately lets SerializationException /
 * IllegalArgumentException propagate on corrupt or partial input; callers treat that as "reject".
 */
object BackupCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    /**
     * Writes format 1. The app only writes format 2 now; this stays so a format-1 file can be built
     * and read back, which is how the frozen format-1 reader is kept honest.
     */
    fun encode(backup: WalletBackup): String = json.encodeToString(backup)

    /** Reads a format-1 file (1.0 and 1.1), to be converted with [BackupUpgrade.v1ToV2]. */
    fun decode(text: String): WalletBackup = json.decodeFromString(text)

    fun encode(backup: WalletBackupV2): String = json.encodeToString(backup)

    /**
     * Reads a format-2 file with the same strict settings as [decode]. A file whose `version` is
     * not 2 is refused with IllegalArgumentException even when its keys happen to fit.
     */
    fun decodeV2(text: String): WalletBackupV2 {
        val backup: WalletBackupV2 = json.decodeFromString(text)
        require(backup.version == BACKUP_FORMAT_V2) {
            "expected backup version $BACKUP_FORMAT_V2, found ${backup.version}"
        }
        return backup
    }
}
