package org.aristonis.mywallet.data.backup

import kotlinx.serialization.SerializationException
import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.TransactionEntity
import org.aristonis.mywallet.domain.error.WalletException

/** The backup format this build reads and writes. A restore of any other version is rejected. */
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
    return backup
}
