package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * The header of a stored entry. Its money lives on [EntryLegEntity] rows; [kind] names the recipe
 * that reads those legs back, and [pairRate] is the exact text of a cross-currency entry's rate.
 *
 * The [date] index serves ranged reads; `id` is the rowid and rides along in every index entry, so
 * `ORDER BY date DESC, id DESC` reads straight off it.
 */
@Entity(
    tableName = "entries",
    indices = [Index(value = ["date"])],
)
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val date: LocalDate,
    val note: String? = null,
    val pairRate: String? = null,
)
