package org.aristonis.mywallet.data.golden

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.data.db.ALL_MIGRATIONS
import org.aristonis.mywallet.data.db.WalletDatabase

/** Opens a migrated copy and always closes/removes it, including when an assertion fails. */
internal suspend fun <T> withFilteredSeed(context: Context, name: String, copies: Int = 1,
    block: suspend (WalletDatabase) -> T): T {
    require(copies >= 1)
    context.deleteDatabase(name)
    val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
    try {
        InstrumentationRegistry.getInstrumentation().context.assets.open("v2-seed/wallet.db").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val db = Room.databaseBuilder(context, WalletDatabase::class.java, name)
            .addMigrations(*ALL_MIGRATIONS).build()
        try {
            db.openHelper.writableDatabase
            duplicateSeed(db, copies)
            return block(db)
        } finally { db.close() }
    } finally { context.deleteDatabase(name) }
}

/** Copies only entries and their receipts; categories/accounts/debts remain shared master rows. */
private fun duplicateSeed(db: WalletDatabase, copies: Int) {
    if (copies == 1) return
    val sql = db.openHelper.writableDatabase
    fun maximum(table: String): Long = sql.query("SELECT COALESCE(MAX(id),0) FROM $table").use {
        check(it.moveToFirst())
        it.getLong(0)
    }
    val entryOffset = maximum("entries") + 1
    val legOffset = maximum("entry_legs") + 1
    val lineOffset = maximum("income_deduction_lines") + 1
    sql.beginTransaction()
    try {
        for (copy in 1 until copies) {
            sql.execSQL("INSERT INTO entries (id,kind,date,note,pairRate) SELECT id+?,kind,date,note,pairRate FROM entries WHERE id<?",
                arrayOf(copy * entryOffset, entryOffset))
            sql.execSQL("INSERT INTO entry_legs (id,entryId,role,position,accountId,debtId,categoryId,subCategoryId,amount,currencyCode,rateToBase) " +
                "SELECT id+?,entryId+?,role,position,accountId,debtId,categoryId,subCategoryId,amount,currencyCode,rateToBase FROM entry_legs WHERE id<?",
                arrayOf(copy * legOffset, copy * entryOffset, legOffset))
            sql.execSQL("INSERT INTO income_deduction_lines (id,entryId,itemId,position,name,mode,value,valueCurrencyCode,timing) " +
                "SELECT id+?,entryId+?,itemId,position,name,mode,value,valueCurrencyCode,timing FROM income_deduction_lines WHERE id<?",
                arrayOf(copy * lineOffset, copy * entryOffset, lineOffset))
        }
        sql.setTransactionSuccessful()
    } finally { sql.endTransaction() }
}
