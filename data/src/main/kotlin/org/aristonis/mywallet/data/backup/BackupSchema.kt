package org.aristonis.mywallet.data.backup

import kotlinx.serialization.Serializable
import org.aristonis.mywallet.data.db.SETTINGS_ROW_ID
import java.time.LocalDate

/**
 * Versioned, Room-independent backup schema. Each DTO mirrors its Room entity's fields (identical
 * names and types; money stays an exact String) but carries no Room annotations, so the on-disk
 * backup format stays stable even when the database schema later evolves. [version] lets a future
 * restore branch on the format it is reading.
 */

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
data class CategoryDto(
    val id: Long = 0,
    val name: String,
    val kind: String,
    val parentId: Long? = null,
)

@Serializable
data class CurrencyDto(
    val code: String,
    val symbol: String,
    val decimalPlaces: Int,
)

@Serializable
data class RateDto(
    val currencyCode: String,
    val rateToBase: String,
)

@Serializable
data class SettingsDto(
    val id: Int = SETTINGS_ROW_ID,
    val baseCurrencyCode: String,
    val theme: String,
    val schemaVersion: Int,
)

@Serializable
data class WalletBackup(
    val version: Int = 1,
    val settings: SettingsDto?,
    val accounts: List<AccountDto>,
    val transactions: List<TransactionDto>,
    val categories: List<CategoryDto>,
    val currencies: List<CurrencyDto>,
    val rates: List<RateDto>,
)
