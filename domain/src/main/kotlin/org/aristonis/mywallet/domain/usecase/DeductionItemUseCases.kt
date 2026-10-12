package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.NameKey
import org.aristonis.mywallet.domain.port.DeductionItemRepository

/** Watches reusable deduction rules. Saved income snapshots are independent of this stream. */
class ObserveDeductionItems(private val items: DeductionItemRepository) {
    operator fun invoke(): Flow<List<DeductionItem>> = items.observeAll()
}

/** Saves a reusable rule without changing any deduction lines already recorded on an income. */
class SaveDeductionItem(private val items: DeductionItemRepository) {
    suspend operator fun invoke(item: DeductionItem): Long {
        // NameKey also rejects names made only of invisible formatting characters.
        NameKey.of(item.name)
        return items.upsert(item.copy(name = item.name.trim()))
    }
}

/** Deletes only rules which are not referenced by a saved income snapshot. */
class DeleteDeductionItem(private val items: DeductionItemRepository) {
    suspend operator fun invoke(id: Long) {
        if (items.findById(id) == null) throw WalletException.DeductionItemNotFound(id)
        if (items.isInUse(id)) throw WalletException.DeductionItemInUse(id)
        items.delete(id)
    }
}

/** Toggles a reusable rule while preserving its identity and all of its other settings. */
class SetDeductionItemEnabled(private val items: DeductionItemRepository) {
    suspend operator fun invoke(id: Long, enabled: Boolean) {
        val item = items.findById(id) ?: throw WalletException.DeductionItemNotFound(id)
        items.upsert(item.copy(enabled = enabled))
    }
}

/** An enabled item offered for one income category and whether the form should preselect it. */
data class DeductionProposal(
    val item: DeductionItem,
    val selectedByDefault: Boolean,
)

/** Lists every enabled rule for manual choice and marks category-linked rules as default selections. */
class ProposeDeductions(private val items: DeductionItemRepository) {
    suspend operator fun invoke(incomeCategoryId: Long): List<DeductionProposal> =
        items.observeAll().first().asSequence()
            .filter(DeductionItem::enabled)
            .map { item ->
                DeductionProposal(
                    item = item,
                    selectedByDefault = incomeCategoryId in item.autoAddCategoryIds,
                )
            }
            .toList()
}
