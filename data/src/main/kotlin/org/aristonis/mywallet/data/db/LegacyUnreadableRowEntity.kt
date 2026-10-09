package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * A raw copy of an old transactions row that the upgrade could not carry over in full, with the
 * [reason] it was set aside, so nothing the owner entered is lost.
 *
 * It is an archive written once by the migration (and by a restore), so it has no foreign keys and
 * no generated id: [id] is the original row's id, and a key action would change the copy.
 */
@Entity(tableName = "legacy_unreadable_rows")
data class LegacyUnreadableRowEntity(
    @PrimaryKey val id: Long,
    val type: String,
    val date: LocalDate,
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
    val reason: String,
)
