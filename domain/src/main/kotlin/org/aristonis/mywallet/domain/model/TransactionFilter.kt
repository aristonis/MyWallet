package org.aristonis.mywallet.domain.model

/** Groups shown above the transaction list; ALL represents an empty type selection in the UI. */
enum class TypeGroup { ALL, INCOME, EXPENSE, TRANSFER, DEBTS }

/** Selections combine with OR within a group and AND between groups; empty groups are unrestricted. */
data class TransactionFilter(
    val types: Set<TypeGroup> = emptySet(),
    val accountIds: Set<Long> = emptySet(),
    val categoryIds: Set<Long> = emptySet(),
) {
    init {
        require(TypeGroup.ALL !in types) { "All is represented by an empty type selection" }
    }

    val activeSheetCount: Int get() = (if (accountIds.isEmpty()) 0 else 1) +
        (if (categoryIds.isEmpty()) 0 else 1)
}
