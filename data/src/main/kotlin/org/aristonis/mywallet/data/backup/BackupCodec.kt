package org.aristonis.mywallet.data.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The single JSON gateway for wallet backups. Reads are strict — unknown keys and missing required
 * fields are rejected rather than silently defaulted — so a decoded backup is guaranteed complete
 * before restore touches it. [decode] deliberately lets SerializationException /
 * IllegalArgumentException propagate on corrupt or partial input; callers treat that as "reject".
 */
object BackupCodec {

    private val json = Json {
        prettyPrint = false
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
     * Checks [backup] exactly as restore does, with no stream involved, so a refused wallet is caught
     * before the caller opens a document. Throws `WalletException.BackupInvalid` on refusal.
     */
    fun validate(backup: WalletBackupV2): RestorableBackup = RestorableBackup(requireRestorable(backup))

    /** Writes a checked backup to the caller-owned stream, which stays open. */
    @OptIn(ExperimentalSerializationApi::class)
    fun encode(backup: RestorableBackup, output: OutputStream) {
        json.encodeToStream(backup.backup, output)
    }

    /** Reads frozen format 1 directly from a caller-owned stream. */
    @OptIn(ExperimentalSerializationApi::class)
    fun decode(input: InputStream): WalletBackup = json.decodeFromStream(input)

    /** Reads frozen format 2 directly from a caller-owned stream. */
    @OptIn(ExperimentalSerializationApi::class)
    fun decodeV2(input: InputStream): WalletBackupV2 = json.decodeFromStream<WalletBackupV2>(input).also {
        require(it.version == BACKUP_FORMAT_V2) { "expected backup version $BACKUP_FORMAT_V2" }
    }

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

/**
 * A format-2 backup that has passed the restore checks. Only [BackupCodec.validate] makes one, so the
 * streaming [BackupCodec.encode] cannot be handed a wallet that restore would refuse.
 */
class RestorableBackup internal constructor(internal val backup: WalletBackupV2)
