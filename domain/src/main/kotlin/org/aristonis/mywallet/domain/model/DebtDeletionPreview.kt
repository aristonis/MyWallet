package org.aristonis.mywallet.domain.model

/**
 * Current entries to remove and income entry ids to retain. Retained incomes are identified
 * without decoding them; their signed debt legs determine what remains after the deletion.
 */
data class DebtDeletionPreview(
    val debt: Debt,
    val entries: List<Transaction>,
    val retainedEntryIds: List<Long>,
    val remainingAfter: Money,
)
