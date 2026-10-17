package org.aristonis.mywallet.data.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromStream
import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CategoryRoleEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.DebtEntity
import org.aristonis.mywallet.data.db.DeductionItemAutoCategoryEntity
import org.aristonis.mywallet.data.db.DeductionItemEntity
import org.aristonis.mywallet.data.db.EntryEntity
import org.aristonis.mywallet.data.db.EntryLegEntity
import org.aristonis.mywallet.data.db.IncomeDeductionLineEntity
import org.aristonis.mywallet.data.db.LegacyUnreadableRowEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.domain.error.WalletException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The backup format this build writes. It reads this one and every older one, each converted up to
 * a readable shape; a newer version is refused.
 *
 * Readers reject keys they do not know, so any change a released build could not read needs a new
 * number. Formats 2 and 3 share DTO fields; format 3 marks the expanded ledger receipts. A changed
 * DTO shape needs frozen DTOs and a converter from the format before. The version is read on
 * its own before the strict decode, so a backup from a newer build is reported as such even when it
 * carries fields this build has never heard of.
 */
const val CURRENT_BACKUP_VERSION = BACKUP_FORMAT_V3

/** Every table as one consistent read, the way an export takes it. A missing settings row is null. */
data class TableSnapshot(
    val settings: SettingsEntity?,
    val accounts: List<AccountEntity>,
    val categories: List<CategoryEntity>,
    val currencies: List<CurrencyEntity>,
    val rates: List<RateEntity>,
    val entries: List<EntryEntity>,
    val legs: List<EntryLegEntity>,
    val debts: List<DebtEntity>,
    val deductionItems: List<DeductionItemEntity>,
    val deductionAutoCategories: List<DeductionItemAutoCategoryEntity>,
    val deductionLines: List<IncomeDeductionLineEntity>,
    val categoryRoles: List<CategoryRoleEntity>,
    val legacyRows: List<LegacyUnreadableRowEntity>,
)

/**
 * Pure, Room-free assembly of a table snapshot into a backup of the current format: entities become
 * DTOs, money stays an exact String. Kept off the Room runtime so it is unit-testable on the JVM.
 */
fun buildBackup(snapshot: TableSnapshot): WalletBackupV2 = WalletBackupV2(
    version = CURRENT_BACKUP_VERSION,
    settings = snapshot.settings?.toDto(),
    accounts = snapshot.accounts.map { it.toDto() },
    categories = snapshot.categories.map { it.toDto() },
    currencies = snapshot.currencies.map { it.toDto() },
    rates = snapshot.rates.map { it.toDto() },
    entries = snapshot.entries.map { it.toDto() },
    legs = snapshot.legs.map { it.toDto() },
    debts = snapshot.debts.map { it.toDto() },
    deductionItems = snapshot.deductionItems.map { it.toDto() },
    deductionAutoCategories = snapshot.deductionAutoCategories.map { it.toDto() },
    deductionLines = snapshot.deductionLines.map { it.toDto() },
    categoryRoles = snapshot.categoryRoles.map { it.toDto() },
    legacyRows = snapshot.legacyRows.map { it.toDto() },
)

/**
 * Decodes backup text of any format this build reads, brings it up to the current format, and
 * checks it can be restored, before a restore may act on it.
 *
 * - A version newer than this build knows (or below the first) is [WalletException.BackupVersionUnsupported].
 * - Malformed or partial input, a file the converter refuses, or one that fails a check is
 *   [WalletException.BackupInvalid].
 *
 * An old file is converted first and then held to exactly the checks a current one gets, so the
 * two can never be judged by different rules. This reads the text through the same readers a
 * restore from a document uses, so the two entry points cannot drift apart.
 */
fun decodeValidated(text: String): WalletBackupV2 = decodeValidatedFrom { text.byteInputStream() }

/**
 * Spools input to private scratch storage so version dispatch can reopen it without holding JSON
 * text in memory. The version-only decoder skips every other field, in any order. Scratch is
 * removed on success, rejection or cancellation; the caller retains ownership of [input].
 *
 * A process killed mid-restore never reaches that cleanup and leaves a plaintext copy of a whole
 * wallet behind, so each restore first removes what earlier ones left. Restores take a lock and
 * run one at a time, so a scratch file found at the start is never one still in use.
 */
fun decodeValidated(input: InputStream, scratchDirectory: File): WalletBackupV2 = synchronized(SCRATCH_LOCK) {
    spoolAndDecode(input, scratchDirectory)
}

private fun spoolAndDecode(input: InputStream, scratchDirectory: File): WalletBackupV2 {
    removeLeftoverScratch(scratchDirectory)
    val scratch = File.createTempFile(SCRATCH_PREFIX, SCRATCH_SUFFIX, scratchDirectory)
    var failure: Throwable? = null
    try {
        scratch.outputStream().use { output -> input.copyTo(output) }
        return decodeValidatedFrom { scratch.inputStream() }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        if (!scratch.delete()) {
            val cleanupError = IOException("Could not remove backup scratch file")
            if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
        }
    }
}

/**
 * Peeks the version, then decodes with that version's reader. [open] is called once per pass and
 * each stream it returns is closed here. A file with no version at all predates the field and is
 * format 1.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun decodeValidatedFrom(open: () -> InputStream): WalletBackupV2 {
    val version = rejectingMalformed {
        open().use { VersionPeek.json.decodeFromStream<VersionPeek>(it).version } ?: BACKUP_FORMAT_V1
    }
    val reader = FORMAT_READERS[version] ?: throw WalletException.BackupVersionUnsupported(version)
    // The checks recompute every name key, so the file they hand back is the one to restore.
    return requireRestorable(rejectingMalformed { open().use(reader) })
}

/** Malformed or partial input, or a converter refusing a file the database could never have held. */
private inline fun <T> rejectingMalformed(block: () -> T): T = try {
    block()
} catch (error: SerializationException) {
    throw WalletException.BackupInvalid(error)
} catch (error: IllegalArgumentException) {
    throw WalletException.BackupInvalid(error)
}

/**
 * Deletes scratch copies earlier restores left behind. Best effort: a copy that cannot be removed
 * now is tried again on the next restore, and must not block this one.
 */
private fun removeLeftoverScratch(scratchDirectory: File) {
    scratchDirectory
        .listFiles { file -> file.isFile && file.name.startsWith(SCRATCH_PREFIX) && file.name.endsWith(SCRATCH_SUFFIX) }
        ?.forEach { it.delete() }
}

private const val SCRATCH_PREFIX = "wallet-restore-"
private const val SCRATCH_SUFFIX = ".json"
private val SCRATCH_LOCK = Any()

/**
 * Each supported format is decoded into the shared restore shape. Format 1 needs a converter;
 * formats 2 and 3 share DTO fields and retain their original version and stored values.
 */
private val FORMAT_READERS: Map<Int, (InputStream) -> WalletBackupV2> = mapOf(
    BACKUP_FORMAT_V1 to { input -> upgradeFromV1(BackupCodec.decode(input)) },
    BACKUP_FORMAT_V2 to { input -> upgradeFromV2(BackupCodec.decodeV2(input)) },
    BACKUP_FORMAT_V3 to { input -> BackupCodec.decodeV2(input) },
)

private fun upgradeFromV1(backup: WalletBackup): WalletBackupV2 = upgradeFromV2(BackupUpgrade.v1ToV2(backup))

/** Formats 2 and 3 share DTO fields; keep the source version and all stored values unchanged. */
private fun upgradeFromV2(backup: WalletBackupV2): WalletBackupV2 = backup

@kotlinx.serialization.Serializable
private data class VersionPeek(val version: Int? = null) {
    companion object {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
