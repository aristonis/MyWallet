package org.aristonis.mywallet.domain.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveTodayTest {
    @Test fun emitsTheCurrentLocalDateImmediatelyOnCollection() = runTest {
        val clock = MutableClock(Instant.parse("2026-10-09T23:30:00Z"), ZoneId.of("Asia/Damascus"))
        assertEquals(listOf(LocalDate.of(2026, 10, 10)), observeToday(clock).take(1).toList())
    }

    @Test fun eachCollectorReadsTheDateWhenItStarts() = runTest {
        val clock = MutableClock(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC)
        val dates = observeToday(clock)
        assertEquals(listOf(LocalDate.of(2026, 10, 10)), dates.take(1).toList())
        clock.now = Instant.parse("2026-10-11T12:00:00Z")
        assertEquals(listOf(LocalDate.of(2026, 10, 11)), dates.take(1).toList())
    }

    @Test fun reEmitsAtTheNextLocalMidnightWithoutAWalletWrite() = runTest {
        val clock = MutableClock(Instant.parse("2026-10-10T23:59:59Z"), ZoneOffset.UTC)
        val seen = mutableListOf<LocalDate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observeToday(clock).take(2).toList(seen) }
        runCurrent()
        advanceTimeBy(999)
        assertEquals(listOf(LocalDate.of(2026, 10, 10)), seen)
        clock.now = Instant.parse("2026-10-11T00:00:00Z")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11)), seen)
    }

    @Test fun recomputesTheNextMidnightAcrossAShortDaylightSavingDay() = runTest {
        val clock = MutableClock(Instant.parse("2026-03-29T00:00:00Z"), ZoneId.of("Europe/London"))
        val seen = mutableListOf<LocalDate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observeToday(clock).take(3).toList(seen) }
        runCurrent()
        advanceTimeBy(SHORT_DAY_MS - 1)
        assertEquals(listOf(LocalDate.of(2026, 3, 29)), seen)
        clock.now = Instant.parse("2026-03-29T23:00:00Z")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(LocalDate.of(2026, 3, 30), seen.last())
        advanceTimeBy(DAY_MS - 1)
        assertEquals(2, seen.size)
        clock.now = Instant.parse("2026-03-30T23:00:00Z")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(LocalDate.of(2026, 3, 31), seen.last())
    }

    @Test fun waitsUntilMidnightAcrossALongDaylightSavingDay() = runTest {
        val clock = MutableClock(Instant.parse("2026-10-24T23:00:00Z"), ZoneId.of("Europe/London"))
        val seen = mutableListOf<LocalDate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observeToday(clock).take(2).toList(seen) }
        runCurrent()
        advanceTimeBy(DAY_MS)
        assertEquals(listOf(LocalDate.of(2026, 10, 25)), seen)
        clock.now = Instant.parse("2026-10-26T00:00:00Z")
        advanceTimeBy(HOUR_MS)
        runCurrent()
        assertEquals(LocalDate.of(2026, 10, 26), seen.last())
    }

    private class MutableClock(var now: Instant, private val timeZone: ZoneId) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = timeZone
        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val SHORT_DAY_MS = 23 * HOUR_MS
    }
}
