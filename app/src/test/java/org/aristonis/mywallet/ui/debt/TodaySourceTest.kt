package org.aristonis.mywallet.ui.debt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class TodaySourceTest {
    @Test fun survivingCollectorRefreshesUsingTheCurrentZoneWithoutAWalletWrite() = runTest {
        var clock = Clock.fixed(Instant.parse("2026-10-12T23:00:00Z"), ZoneOffset.UTC)
        val source = TodaySource { clock }
        val seen = mutableListOf<LocalDate>()
        backgroundScope.launch { source.dates.collect { seen.add(it) } }
        runCurrent()
        assertEquals(LocalDate.of(2026, 10, 12), seen.last())
        clock = clock.withZone(ZoneOffset.ofHours(3))
        source.refresh()
        runCurrent()
        assertEquals(LocalDate.of(2026, 10, 13), seen.last())
    }

    @Test fun restartedCollectorReadsTheCurrentZoneEvenWithoutAnExplicitRefresh() = runTest {
        var clock = Clock.fixed(Instant.parse("2026-10-12T23:00:00Z"), ZoneOffset.UTC)
        val source = TodaySource { clock }
        val first = mutableListOf<LocalDate>()
        val subscriber = backgroundScope.launch { source.dates.collect { first.add(it) } }
        runCurrent()
        assertEquals(LocalDate.of(2026, 10, 12), first.last())
        subscriber.cancelAndJoin()
        clock = clock.withZone(ZoneOffset.ofHours(3))
        val restarted = mutableListOf<LocalDate>()
        backgroundScope.launch { source.dates.collect { restarted.add(it) } }
        runCurrent()
        assertEquals(LocalDate.of(2026, 10, 13), restarted.last())
    }
    @Test fun defaultSourceUsesTheCurrentDeviceZoneForRefreshAndFreshCollectors() = runTest {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT-12:00"))
            val source = TodaySource()
            val surviving = mutableListOf<LocalDate>()
            val subscriber = backgroundScope.launch { source.dates.collect { surviving.add(it) } }
            runCurrent()
            val originalDate = surviving.last()
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+14:00"))
            source.refresh()
            runCurrent()
            org.junit.Assert.assertTrue(surviving.last().isAfter(originalDate))
            subscriber.cancelAndJoin()
            val restarted = mutableListOf<LocalDate>()
            backgroundScope.launch { source.dates.collect { restarted.add(it) } }
            runCurrent()
            assertEquals(LocalDate.now(), restarted.last())
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

}
