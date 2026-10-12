package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.port.DeductionItemRepository

/** Explicit deduction storage for entry fixtures that have no deduction rules yet. */
internal class FakeDeductionItemRepository : DeductionItemRepository {
    private val items = MutableStateFlow<List<DeductionItem>>(emptyList())

    override fun observeAll(): Flow<List<DeductionItem>> = items
    override suspend fun findById(id: Long): DeductionItem? = items.value.find { it.id == id }
    override suspend fun upsert(item: DeductionItem): Long {
        val id = item.id.takeIf { it > 0 } ?: ((items.value.maxOfOrNull { it.id } ?: 0) + 1)
        items.value = items.value.filterNot { it.id == id } + item.copy(id = id)
        return id
    }
    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
    override suspend fun isInUse(id: Long): Boolean = false
}
