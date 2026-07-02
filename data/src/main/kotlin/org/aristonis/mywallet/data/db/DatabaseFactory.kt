package org.aristonis.mywallet.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Builds the Room database — keeps all Room construction inside `:data`. The `onCreate` callback
 * seeds the default currencies and categories the first time the DB is created (runs once).
 */
object DatabaseFactory {

    fun create(context: Context): WalletDatabase =
        Room.databaseBuilder(context, WalletDatabase::class.java, "wallet.db")
            .addCallback(SeedCallback)
            .build()

    private object SeedCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            DefaultData.currencies.forEach {
                db.execSQL(
                    "INSERT INTO currencies (code, symbol, decimalPlaces) VALUES (?, ?, ?)",
                    arrayOf<Any?>(it.code, it.symbol, it.decimalPlaces),
                )
            }
            DefaultData.categories.forEach {
                db.execSQL(
                    "INSERT INTO categories (name, kind, parentId) VALUES (?, ?, NULL)",
                    arrayOf<Any?>(it.name, it.kind.name),
                )
            }
        }
    }
}
