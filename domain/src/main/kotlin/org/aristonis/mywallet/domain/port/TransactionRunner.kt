package org.aristonis.mywallet.domain.port

/**
 * Runs several repository writes as one database transaction: all of them land, or none do. A use
 * case that must not leave half its work behind (a rate kept for a transaction that was refused)
 * wraps its writes in this; the data layer decides how a transaction is opened.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
