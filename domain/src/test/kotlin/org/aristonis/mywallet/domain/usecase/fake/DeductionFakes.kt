package org.aristonis.mywallet.domain.usecase.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.NameKey
import org.aristonis.mywallet.domain.port.DeductionItemRepository
import java.math.BigDecimal

/**
 * In-memory [DeductionItemRepository] that keeps the same contract as the Room one: a new item gets an
 * id, an unknown positive id is refused, and a name that folds to another item's key is refused.
 */
class FakeDeductionItemRepository(
    initialItems: List<DeductionItem> = emptyList(),
    private val usedIds: Set<Long> = emptySet(),
) : DeductionItemRepository {
    private val items = MutableStateFlow(initialItems)
    private var nextId = (initialItems.maxOfOrNull(DeductionItem::id) ?: 0L) + 1

    override fun observeAll(): Flow<List<DeductionItem>> = items

    override suspend fun findById(id: Long): DeductionItem? = items.value.firstOrNull { it.id == id }

    override suspend fun upsert(item: DeductionItem): Long {
        val key = NameKey.of(item.name)
        if (items.value.any { it.id != item.id && NameKey.of(it.name) == key }) {
            throw WalletException.DeductionItemNameTaken(item.name)
        }
        if (item.id != 0L && findById(item.id) == null) throw WalletException.DeductionItemNotFound(item.id)
        val id = if (item.id == 0L) nextId++ else item.id
        val saved = item.copy(id = id)
        items.value = if (items.value.any { it.id == id }) {
            items.value.map { if (it.id == id) saved else it }
        } else {
            items.value + saved
        }
        return id
    }

    override suspend fun delete(id: Long) {
        items.value = items.value.filterNot { it.id == id }
    }

    override suspend fun isInUse(id: Long): Boolean = id in usedIds
}

/** A valid percentage rule owed later unless a test says otherwise. */
fun deductionItem(
    id: Long = 0,
    name: String,
    value: String = "10",
    timing: DeductionTiming = DeductionTiming.LATER,
    expenseCategoryId: Long = 20,
    autoAddCategoryIds: Set<Long> = emptySet(),
    enabled: Boolean = true,
) = DeductionItem(
    id = id,
    name = name,
    mode = DeductionMode.PERCENT,
    value = BigDecimal(value),
    timing = timing,
    expenseCategoryId = expenseCategoryId,
    autoAddCategoryIds = autoAddCategoryIds,
    enabled = enabled,
)
