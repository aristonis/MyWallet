package org.aristonis.mywallet.data.backup

import kotlinx.serialization.SerializationException
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

/**
 * The backup format this build writes. It reads this one and every older one, each converted up to
 * this one; a newer version is refused.
 *
 * Readers reject keys they do not know, so any change a released build could not read needs a new
 * number, a frozen DTO set for it, and a converter from the format before. The version is read on
 * its own before the strict decode, so a backup from a newer build is reported as such even when it
 * carries fields this build has never heard of.
 */
const val CURRENT_BACKUP_VERSION = BACKUP_FORMAT_V2

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
 * two can never be judged by different rules.
 */
fun decodeValidated(text: String): WalletBackupV2 {
    // A file with no version at all predates the field and is format 1.
    val version = versionOf(text) ?: BACKUP_FORMAT_V1
    val reader = FORMAT_READERS[version] ?: throw WalletException.BackupVersionUnsupported(version)
    val backup = try {
        reader(text)
    } catch (e: SerializationException) {
        throw WalletException.BackupInvalid(e)
    } catch (e: IllegalArgumentException) {
        // Also what the converters throw for a file the database could never have held.
        throw WalletException.BackupInvalid(e)
    }
    requireRestorable(backup)
    return backup
}

/**
 * How each format this build reads becomes the current one: decoded with its own frozen DTOs, then
 * passed up the chain of one-step converters from its version. A new format adds its reader here,
 * its converter from the format before, and that converter as the next step of the chain.
 */
private val FORMAT_READERS: Map<Int, (String) -> WalletBackupV2> = mapOf(
    BACKUP_FORMAT_V1 to { text -> upgradeFromV1(BackupCodec.decode(text)) },
    BACKUP_FORMAT_V2 to { text -> upgradeFromV2(BackupCodec.decodeV2(text)) },
)

private fun upgradeFromV1(backup: WalletBackup): WalletBackupV2 = upgradeFromV2(BackupUpgrade.v1ToV2(backup))

/** Format 2 is the current format, so the chain ends here. */
private fun upgradeFromV2(backup: WalletBackupV2): WalletBackupV2 = backup

/** Reads only the format version, tolerating every other field; null when the text has none or is not JSON. */
private fun versionOf(text: String): Int? = runCatching {
    VersionPeek.json.decodeFromString(VersionPeek.serializer(), text).version
}.getOrNull()

@kotlinx.serialization.Serializable
private data class VersionPeek(val version: Int? = null) {
    companion object {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
