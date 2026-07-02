package org.aristonis.mywallet.data.db

import androidx.room.TypeConverter
import java.time.LocalDate

/**
 * Room can't persist a [LocalDate] directly, so these tell it how: store it as the epoch-day
 * (a Long). Registered on the database via `@TypeConverters`. Money stays a plain TEXT column,
 * converted in the mappers instead — that keeps BigDecimal logic out of Room.
 */
class Converters {
    @TypeConverter
    fun localDateToEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(epochDay: Long?): LocalDate? = epochDay?.let(LocalDate::ofEpochDay)
}
