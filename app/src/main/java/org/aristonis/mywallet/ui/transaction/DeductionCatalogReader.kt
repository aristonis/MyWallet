package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.port.DeductionItemRepository
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage

/**
 * Keeps an income form's deduction catalog read, for the add and the edit form alike. A failed read
 * ends the flow, so [start] is also the retry: it drops the old read and begins a new one.
 */
internal class DeductionCatalogReader(
    private val scope: CoroutineScope,
    private val items: DeductionItemRepository,
    private val errors: ErrorReporter,
    private val onItems: (List<DeductionItem>) -> Unit,
    private val onFailure: (UiMessage) -> Unit,
) {
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = items.observeAll()
            .onEach { onItems(it) }
            .catch { e ->
                if (e is CancellationException) throw e
                errors.report("Income deductions could not be read", e)
                onFailure(e.toUiMessage())
            }
            .launchIn(scope)
    }
}
