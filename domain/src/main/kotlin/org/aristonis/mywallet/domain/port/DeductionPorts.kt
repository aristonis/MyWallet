package org.aristonis.mywallet.domain.port

import kotlinx.coroutines.flow.Flow
import org.aristonis.mywallet.domain.model.DeductionItem

/** Storage boundary for the owner's reusable deduction rules. */
interface DeductionItemRepository {
    fun observeAll(): Flow<List<DeductionItem>>
    suspend fun findById(id: Long): DeductionItem?

    /** Inserts an item with id 0 or replaces an existing item, returning its stable id. */
    suspend fun upsert(item: DeductionItem): Long
    suspend fun delete(id: Long)

    /** True when a saved income line snapshots this item and deletion must be refused. */
    suspend fun isInUse(id: Long): Boolean
}
