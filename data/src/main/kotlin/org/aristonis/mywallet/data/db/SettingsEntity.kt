package org.aristonis.mywallet.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-row settings table (always id = [SETTINGS_ROW_ID]). */
const val SETTINGS_ROW_ID = 0

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = SETTINGS_ROW_ID,
    val baseCurrencyCode: String,
    val theme: String, // SYSTEM | LIGHT | DARK
    val schemaVersion: Int,
    // Must equal the migration's DEFAULT so Room's schema check passes on a migrated database.
    @ColumnInfo(defaultValue = "1") val saveTransactionRates: Boolean = true,
)
