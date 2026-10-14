package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.ui.message.UiMessage

/**
 * What the transaction page shows. [row] is the saved entry in the list's own row model (gross,
 * account movement, fee, deductions and note as stored); it is null while loading, when the entry
 * does not exist ([notFound]) and when the read failed ([loadFailure]).
 *
 * A delete keeps the entry in [undoableDelete] until the page lets it go, as the editor does;
 * [deleted] says the page can close. [deleteError] is a refused delete, such as a debt that would
 * go below zero, or an undo that could not put the entry back; the entry stays as it was.
 * [isDeleting] is true while a delete or its undo is being written, so neither can start twice.
 */
internal data class TransactionDetailUiState(
    val isLoading: Boolean = true,
    val row: TransactionRow? = null,
    val notFound: Boolean = false,
    val loadFailure: UiMessage? = null,
    val isDeleting: Boolean = false,
    val deleteError: UiMessage? = null,
    val undoableDelete: UndoBundle? = null,
    val deleted: Boolean = false,
) {
    /**
     * Edit and Delete only make sense on an entry that is on screen and still stored: not while a
     * delete or undo is running, and not while the entry waits on its undo window.
     */
    val canAct: Boolean
        get() = row != null && !isLoading && !isDeleting && undoableDelete == null && !deleted
}
