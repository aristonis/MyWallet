package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.aristonis.mywallet.domain.usecase.SuggestCounterparties

internal class CounterpartySuggestions(private val scope: CoroutineScope, private val suggest: SuggestCounterparties,
    private val onResult: (List<String>) -> Unit, private val onFailure: (Exception) -> Unit) {
    private var generation = 0L
    private var job: Job? = null

    fun invalidate() { generation++; job?.cancel(); job = null }

    fun request(prefix: String) {
        invalidate()
        if (prefix.isBlank()) { onResult(emptyList()); return }
        val requested = generation
        job = scope.launch {
            try {
                val names = suggest(prefix)
                if (requested == generation) onResult(names)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (requested == generation) onFailure(e) }
        }
    }
}
