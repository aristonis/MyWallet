package org.aristonis.mywallet.domain.model

/**
 * What a delete removed, kept so an undo can put it back exactly: each entry as it was stored, at
 * its own id, with the rates it was recorded with.
 *
 * @property debts The debts whose details the delete cleared. No entry kind touches a debt yet, so
 *   nothing can be put here; the element type becomes the debt model once debts exist.
 */
data class UndoBundle(
    val entries: List<Transaction>,
    val debts: List<Nothing> = emptyList(),
)
