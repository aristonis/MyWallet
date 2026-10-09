package org.aristonis.mywallet.data.backup

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Backup format 1, as 1.0 and 1.1 wrote it: one `transactions` list in the old single-table shape.
 *
 * Frozen. The app no longer writes format 1, but files from those releases must restore for as long
 * as anyone keeps one, so these classes only ever decode; [BackupUpgrade.v1ToV2] turns what they
 * read into format 2. Changing a field here would make some of those files unreadable.
 */

/** The `version` a format-1 file carries; a file with no version at all is read as this one. */
const val BACKUP_FORMAT_V1 = 1

@Serializable
data class AccountDto(
    val id: Long = 0,
    val name: String,
    val typeKey: String,
    val currencyCode: String,
    val openingBalanceAmount: String,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)

@Serializable
data class TransactionDto(
    val id: Long = 0,
    val type: String,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val note: String? = null,
    val primaryAccountId: Long,
    val primaryAmount: String,
    val primaryCurrency: String,
    val categoryId: Long? = null,
    val subCategoryId: Long? = null,
    val secondaryAccountId: Long? = null,
    val secondaryAmount: String? = null,
    val secondaryCurrency: String? = null,
    val rateUsed: String? = null,
)

@Serializable
data class WalletBackup(
    val version: Int = BACKUP_FORMAT_V1,
    val settings: SettingsDto?,
    val accounts: List<AccountDto>,
    val transactions: List<TransactionDto>,
    val categories: List<CategoryDto>,
    val currencies: List<CurrencyDto>,
    val rates: List<RateDto>,
)
