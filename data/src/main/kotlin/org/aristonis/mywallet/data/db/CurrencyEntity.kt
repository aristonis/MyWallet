package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "currencies")
data class CurrencyEntity(
    @PrimaryKey val code: String,
    val symbol: String,
    val decimalPlaces: Int,
)
