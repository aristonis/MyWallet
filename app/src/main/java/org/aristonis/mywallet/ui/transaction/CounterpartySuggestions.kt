package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.aristonis.mywallet.domain.usecase.SuggestCounterparties

/**
 * Looks up earlier counterparty names as the person types. Only the latest request may report: a late
 * answer for an older prefix, or one that arrives after a name was chosen, is dropped.
 */
internal class CounterpartySuggestions(
    private val scope: CoroutineScope,
    private val suggest: SuggestCounterparties,
    private val onResult: (List<String>) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private var generation = 0L
    private var job: Job? = null

    /** Drops any lookup in flight, so its answer is never reported. */
    fun invalidate() {
        generation++
        job?.cancel()
        job = null
    }

    /** Looks up names for [prefix]; a blank prefix reports an empty list at once. */
    fun request(prefix: String) {
        invalidate()
        if (prefix.isBlank()) {
            onResult(emptyList())
            return
        }
        val requested = generation
        job = scope.launch {
            try {
                val names = suggest(prefix)
                if (requested == generation) onResult(names)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requested == generation) onFailure(e)
            }
        }
    }
}
