package org.aristonis.mywallet.domain.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Reads the local date when collected and again at each local midnight. The next midnight is
 * calculated in the clock's zone each time, since a daylight-saving day can be shorter or longer.
 */
fun observeToday(clock: Clock = Clock.systemDefaultZone()): Flow<LocalDate> = flow {
    while (true) {
        emit(LocalDate.now(clock))
        val now = clock.instant()
        val nextMidnight = now.atZone(clock.zone).toLocalDate().plusDays(1).atStartOfDay(clock.zone)
        // Rounding sub-millisecond waits down to zero would spin instead of suspending.
        delay(Duration.between(now, nextMidnight.toInstant()).toMillis().coerceAtLeast(1L))
    }
}.distinctUntilChanged()
