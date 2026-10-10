package org.aristonis.mywallet.ui.debt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import org.aristonis.mywallet.domain.service.observeToday
import java.time.Clock
import java.time.LocalDate

/** Reopens the midnight timer with the current device zone on resume and on each collection. */
@OptIn(ExperimentalCoroutinesApi::class)
class TodaySource(private val clockFactory: () -> Clock = { Clock.systemDefaultZone() }) {
    private val revision = MutableStateFlow(0L)
    val dates: Flow<LocalDate> = flow {
        emitAll(revision.flatMapLatest { observeToday(clockFactory()) })
    }.distinctUntilChanged()

    /** Today in the device zone right now, for a reader that needs it once rather than as a stream. */
    fun now(): LocalDate = LocalDate.now(clockFactory())

    fun refresh() { revision.update { it + 1 } }
}
