package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rates")
data class RateEntity(
    @PrimaryKey val currencyCode: String,
    val rateToBase: String, // BigDecimal as TEXT
)
