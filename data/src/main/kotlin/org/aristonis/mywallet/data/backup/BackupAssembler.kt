package org.aristonis.mywallet.data.backup

import kotlinx.serialization.SerializationException
import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.TransactionEntity
import org.aristonis.mywallet.data.db.toDomain
import org.aristonis.mywallet.domain.error.WalletException

/**
 * The backup format this build reads and writes. A restore of any other version is rejected.
 *
 * Fields added since the first release (the rate switch) carry defaults, so an older backup still
 * reads and this number did not move. A change an older build could not read must bump it. The
 * version is read on its own before the strict decode, so a backup from a newer build is reported
 * as such even when it carries fields this build has never heard of.
 */
const val CURRENT_BACKUP_VERSION = 1

/**
 * Pure, Room-free assembly of a one-shot table snapshot into a versioned [WalletBackup]: entities
 * become DTOs, money stays an exact String, and a missing settings row (pre-onboarding) maps to a
 * null settings block. Kept off the Room runtime so it is unit-testable on the JVM.
 */
fun buildBackup(
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    currencies: List<CurrencyEntity>,
    rates: List<RateEntity>,
    settings: SettingsEntity?,
): WalletBackup = WalletBackup(
    version = CURRENT_BACKUP_VERSION,
    settings = settings?.toDto(),
    accounts = accounts.map { it.toDto() },
    transactions = transactions.map { it.toDto() },
    categories = categories.map { it.toDto() },
    currencies = currencies.map { it.toDto() },
    rates = rates.map { it.toDto() },
)

/**
 * Decodes and validates backup text before a restore may act on it. Malformed or partial input —
 * which [BackupCodec] surfaces as SerializationException / IllegalArgumentException — becomes a
 * [WalletException.BackupInvalid]; a well-formed document written in a different format version
 * becomes a [WalletException.BackupVersionUnsupported]. Only a valid current-version backup returns.
 */
fun decodeValidated(text: String): WalletBackup {
    val version = versionOf(text)
    if (version != null && version != CURRENT_BACKUP_VERSION) {
        throw WalletException.BackupVersionUnsupported(version)
    }
    val backup = try {
        BackupCodec.decode(text)
    } catch (e: SerializationException) {
        throw WalletException.BackupInvalid(e)
    } catch (e: IllegalArgumentException) {
        throw WalletException.BackupInvalid(e)
    }
    // Exact-match reject: an unrecognized version is refused rather than guessed at. When the format
    // first changes, add an upgrade branch here that migrates an older backup forward instead of
    // rejecting it — an offline backup must stay restorable across app versions.
    if (backup.version != CURRENT_BACKUP_VERSION) {
        throw WalletException.BackupVersionUnsupported(backup.version)
    }
    // A real wallet backup is always taken after onboarding, so it always carries settings (the base
    // currency lives there). A settings-less document would restore a wallet with no base currency
    // and bounce the app straight back into onboarding — refuse it rather than restore a half-state.
    if (backup.settings == null) {
        throw WalletException.BackupInvalid()
    }
    // Transactions carry a foreign key on their sub-category, so a document naming a category it
    // does not itself contain aborts the restore part-way with a raw SQLite error — which reaches
    // the user as a generic failure with nothing to act on. Backups are plain JSON in an open-source
    // app and are edited by hand, so this is reachable. Rejecting it here names the real problem
    // before a single row is written.
    val categoryIds = backup.categories.mapTo(HashSet()) { it.id }
    val hasDanglingCategory = backup.transactions.any { tx ->
        tx.categoryId?.let { it !in categoryIds } == true ||
            tx.subCategoryId?.let { it !in categoryIds } == true
    }
    if (hasDanglingCategory) {
        throw WalletException.BackupInvalid()
    }
    // The category tree has to hold up too, and nothing in the database enforces it: `parentId`
    // carries no foreign key, so a broken link survives the restore and only shows itself later,
    // when deleting such a category re-files its transactions onto an id that does not exist — with
    // no error, because that column has no constraint either. A self-parent, a parent that is itself
    // a child, or an id of zero all produce that, so they are refused here where they can still be
    // named.
    val byId = backup.categories.associateBy { it.id }
    val treeIsBroken = backup.categories.any { category ->
        val parentId = category.parentId
        category.id == 0L ||
            (
                parentId != null && (
                    parentId == category.id ||
                        byId[parentId] == null ||
                        byId.getValue(parentId).parentId != null ||
                        byId.getValue(parentId).kind != category.kind
                    )
                )
    }
    if (treeIsBroken) {
        throw WalletException.BackupInvalid()
    }
    if (!everyRowIsReadable(backup)) {
        throw WalletException.BackupInvalid()
    }
    return backup
}

/**
 * Whether the app could read every row back once restored. Kinds, themes and amounts are plain
 * strings in the file, so the JSON decode accepts values the app cannot turn into a transaction, a
 * theme or a number: a kind added by a newer build, a hand edit. Restored, such a row fails every read
 * of its table, and a bad theme stops the app at launch, before Settings can be reached to restore
 * anything else. Each row is read the way the database will read it, so nothing that passes here can
 * fail there.
 */
private fun everyRowIsReadable(backup: WalletBackup): Boolean = try {
    backup.settings?.toEntity()?.toDomain()
    backup.accounts.forEach { it.toEntity().toDomain() }
    backup.categories.forEach { it.toEntity().toDomain() }
    backup.currencies.forEach { it.toEntity().toDomain() }
    backup.rates.forEach { it.toEntity().toDomain() }
    backup.transactions.forEach { it.toEntity().toDomain() }
    true
} catch (e: IllegalArgumentException) {
    // An unknown enum name, or a number that does not parse (NumberFormatException is one of these).
    false
} catch (e: IllegalStateException) {
    // An unknown transaction kind, or a transfer missing one of its legs.
    false
}

/** Reads only the format version, tolerating every other field; null when the text is not a backup. */
private fun versionOf(text: String): Int? = runCatching {
    VersionPeek.json.decodeFromString(VersionPeek.serializer(), text).version
}.getOrNull()

@kotlinx.serialization.Serializable
private data class VersionPeek(val version: Int? = null) {
    companion object {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
