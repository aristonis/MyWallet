package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The persistence shape of an account — a flat table row (NOT the rich domain [Account]).
 * Money is stored as TEXT (exact BigDecimal string) + a currency code; a mapper bridges to the
 * domain type. Keeping the entity dumb keeps the domain free of Room annotations.
 *
 * [creditLimit], [defaultFeeMode] and [defaultFeeValue] are declared last, in this order, and have no
 * default value: the upgrade adds them with `ALTER TABLE ... ADD COLUMN`, which appends them in that
 * order with no default, so a fresh table and an upgraded one come out the same shape.
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
    val creditLimit: String? = null,
    val defaultFeeMode: String? = null,
    val defaultFeeValue: String? = null,
)
