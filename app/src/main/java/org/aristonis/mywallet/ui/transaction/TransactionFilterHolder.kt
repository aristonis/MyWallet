package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aristonis.mywallet.domain.model.TransactionFilter
import org.aristonis.mywallet.domain.model.TypeGroup

/** Stores bundle-safe selections so returning to this list preserves every chosen value. */
class TransactionFilterHolder(private val savedState: SavedStateHandle) {
    private val current = MutableStateFlow(TransactionFilter(
        types = savedState.get<ArrayList<String>>(TYPES_KEY).orEmpty().mapNotNull { name ->
            TypeGroup.entries.firstOrNull { it.name == name && it != TypeGroup.ALL }
        }.toSet(),
        accountIds = savedState.get<LongArray>(ACCOUNTS_KEY)?.toSet().orEmpty(),
        categoryIds = savedState.get<LongArray>(CATEGORIES_KEY)?.toSet().orEmpty(),
    ))
    val filter = current.asStateFlow()

    fun toggleType(type: TypeGroup) = update(current.value.copy(
        types = if (type == TypeGroup.ALL) emptySet() else current.value.types.toggled(type),
    ))
    fun toggleAccount(id: Long) = update(current.value.copy(accountIds = current.value.accountIds.toggled(id)))
    fun toggleCategory(id: Long) = update(current.value.copy(categoryIds = current.value.categoryIds.toggled(id)))
    fun clearAccounts() = update(current.value.copy(accountIds = emptySet()))
    fun clearCategories() = update(current.value.copy(categoryIds = emptySet()))
    fun clear() = update(TransactionFilter())

    private fun update(value: TransactionFilter) {
        savedState[TYPES_KEY] = ArrayList(value.types.map { it.name })
        savedState[ACCOUNTS_KEY] = value.accountIds.toLongArray()
        savedState[CATEGORIES_KEY] = value.categoryIds.toLongArray()
        current.value = value
    }

    private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value

    private companion object {
        const val TYPES_KEY = "transactions.filter.types"
        const val ACCOUNTS_KEY = "transactions.filter.accounts"
        const val CATEGORIES_KEY = "transactions.filter.categories"
    }
}
