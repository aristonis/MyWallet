package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The persistence shape of an account — a flat table row (NOT the rich domain [Account]).
 * Money is stored as TEXT (exact BigDecimal string) + a currency code; a mapper bridges to the
 * domain type. Keeping the entity dumb keeps the domain free of Room annotations.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val typeKey: String,
    val currencyCode: String,
    val openingBalanceAmount: String,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)
