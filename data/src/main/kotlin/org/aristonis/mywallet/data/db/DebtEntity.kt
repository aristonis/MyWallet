package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * A person or party money is owed to or by. The remaining amount is never stored; it is the sum of
 * the debt's legs.
 *
 * The unique `(side, currencyCode, counterpartyKey)` index keeps one row per name, side and currency,
 * so two spellings of the same name ("Ali", " ali") can never split one debt in two.
 */
@Entity(
    tableName = "debts",
    indices = [Index(value = ["side", "currencyCode", "counterpartyKey"], unique = true)],
)
data class DebtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val counterparty: String,
    val counterpartyKey: String,
    val side: String,
    val currencyCode: String,
    val dueDate: LocalDate? = null,
    val note: String? = null,
)
