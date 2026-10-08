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

    /**
     * There is deliberately no `fallbackToDestructiveMigration()` here, and adding one would be a
     * regression rather than a fix. It turns "this database does not match the code" into "delete
     * every account, transaction and rate the user has, silently, on launch" — the one failure mode
     * this app cannot recover from, since the data is offline and exists nowhere else. Room
     * throwing instead is the correct outcome: before release it means a developer reinstalls, and
     * after release it means a missing migration is caught in testing rather than in the field.
     */
    fun create(context: Context): WalletDatabase =
        Room.databaseBuilder(context, WalletDatabase::class.java, "wallet.db")
            .addCallback(SeedCallback)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    internal object SeedCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            DefaultData.currencies.forEach {
                db.execSQL(
                    "INSERT INTO currencies (code, symbol, decimalPlaces) VALUES (?, ?, ?)",
                    arrayOf<Any?>(it.code, it.symbol, it.decimalPlaces),
                )
            }
            DefaultData.categories.forEach {
                db.execSQL(
                    "INSERT INTO categories (name, kind, parentId) VALUES (?, ?, $TOP_LEVEL_PARENT_ID)",
                    arrayOf<Any?>(it.name, it.kind.name),
                )
            }
            DefaultData.subCategories.forEach { insertSubCategory(db, it) }
        }

        /**
         * The parents were only just inserted, so their ids exist but are unknown here. Selecting
         * the parent row inside the INSERT resolves the id and copies its kind in one statement —
         * no read-back, and no chance of filing a child under the wrong kind.
         */
        private fun insertSubCategory(db: SupportSQLiteDatabase, seed: DefaultData.SubCategorySeed) {
            db.compileStatement(
                "INSERT INTO categories (name, kind, parentId) " +
                    "SELECT ?, kind, id FROM categories WHERE name = ? AND parentId = $TOP_LEVEL_PARENT_ID",
            ).use { statement ->
                statement.bindString(1, seed.name)
                statement.bindString(2, seed.parentName)
                // An unmatched parent inserts nothing and returns -1. Left unchecked, the wallet
                // would come up quietly missing sub-categories with no hint as to why.
                check(statement.executeInsert() != -1L) {
                    "seed sub-category \"${seed.name}\" found no top-level parent named \"${seed.parentName}\""
                }
            }
        }
    }
}
