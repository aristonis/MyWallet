package org.aristonis.mywallet.data.repo

import androidx.room.withTransaction
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.port.TransactionRunner

/** Opens one Room transaction around the block, so every write inside it lands or none does. */
class RoomTransactionRunner(private val database: WalletDatabase) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction { block() }
}
