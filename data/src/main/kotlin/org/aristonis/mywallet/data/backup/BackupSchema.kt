package org.aristonis.mywallet.data.backup

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import org.aristonis.mywallet.data.db.SETTINGS_ROW_ID
import java.time.LocalDate

/**
 * The backup DTOs that format 1 and format 2 share. Each mirrors its Room entity's fields (identical
 * names and types; money stays an exact String) but carries no Room annotations, so the on-disk
 * format stays stable even when the database schema later evolves.
 *
 * These are frozen: both formats are read by every later build, and a reader rejects keys it does
 * not know, so a field added here would change two released formats at once. A table that needs a
 * new field gets its own DTO in a new format instead. Format 1's own DTOs are in BackupSchemaV1.kt,
 * format 2's in BackupSchemaV2.kt.
 */

/**
 * [systemKey] defaults to null so a backup written before fallback buckets existed still decodes —
 * its categories are all user categories, which is exactly what a missing key means. Restoring a
 * newer backup keeps the key, so the bucket comes back protected rather than as an ordinary
 * category the user can rename or delete.
 */
@Serializable
data class CategoryDto(
    val id: Long = 0,
    val name: String,
    val kind: String,
    val parentId: Long? = null,
    val systemKey: String? = null,
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
    // Declared last with a default so a backup written before the switch existed still reads, as on.
    // Left out while it holds that default: the first release rejects keys it does not know, so a
    // backup taken with the switch untouched stays one that release can restore.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val saveTransactionRates: Boolean = true,
)
