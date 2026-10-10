package org.aristonis.mywallet.domain.model

/**
 * What a delete removed, kept so an undo can put it back exactly: each entry as it was stored, at
 * its own id, with the rates it was recorded with.
 *
 * @property debts Snapshots only for debts whose details the delete cleared. A single-entry delete
 *   leaves details alone and carries none, so undo preserves later changes to those details.
 */
data class UndoBundle(
    val entries: List<Transaction>,
    val debts: List<Debt> = emptyList(),
)
