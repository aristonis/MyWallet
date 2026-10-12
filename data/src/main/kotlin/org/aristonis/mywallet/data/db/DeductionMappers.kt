package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.NameKey
import java.math.BigDecimal

/**
 * Rebuilds the rule and its auto-category links from storage. A mismatched persisted name key or
 * invalid enum/decimal/value means key lookups could miss or apply a different rule, so the row is
 * rejected with its id instead of being exposed as a valid item.
 */
fun DeductionItemEntity.toDomain(autoAddCategoryIds: Set<Long> = emptySet()): DeductionItem {
    return try {
        DeductionItem(
            id = id,
            name = name,
            mode = DeductionMode.valueOf(mode),
            value = BigDecimal(value),
            valueCurrencyCode = valueCurrencyCode,
            timing = DeductionTiming.valueOf(timing),
            expenseCategoryId = expenseCategoryId,
            autoAddCategoryIds = autoAddCategoryIds,
            enabled = enabled,
            sortOrder = sortOrder,
        ).also { mapped ->
            require(NameKey.of(mapped.name).value == nameKey) {
                "deduction item $id stores a key that is not the key of its name"
            }
        }
    } catch (e: IllegalArgumentException) {
        throw WalletException.UnreadableRow(id, DEDUCTION_ITEM_KIND, e)
    } catch (e: WalletException) {
        throw WalletException.UnreadableRow(id, DEDUCTION_ITEM_KIND, e)
    }
}

/** Trims only the displayed spelling; [NameKey] stores the comparison form independently. */
fun DeductionItem.toEntity(): DeductionItemEntity {
    val displayName = name.trim()
    return DeductionItemEntity(
        id = id,
        name = displayName,
        nameKey = NameKey.of(displayName).value,
        mode = mode.name,
        value = value.toPlainString(),
        valueCurrencyCode = valueCurrencyCode,
        timing = timing.name,
        expenseCategoryId = expenseCategoryId,
        enabled = enabled,
        sortOrder = sortOrder,
    )
}

private const val DEDUCTION_ITEM_KIND = "deduction item"
