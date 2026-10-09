package org.aristonis.mywallet.ui.load

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Loading shows only when the user asks to try again: never on the first read and never when a
 * hidden screen comes back and reads again, or the numbers on screen would flash a spinner.
 */
class ReadAttemptsTest {

    @Test
    fun theFirstReadGoesStraightToItsData() = runTest {
        val attempts = ReadAttempts()
        val seen = mutableListOf<String>()
        val job = launch { attempts.reading({ "loading" }) { flow { emit("data") } }.collect { seen += it } }
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf("data"), seen)
    }

    @Test
    fun aRetryShowsLoadingThenReadsAgain() = runTest {
        val attempts = ReadAttempts()
        var reads = 0
        val seen = mutableListOf<String>()
        val job = launch { attempts.reading({ "loading" }) { flow { reads++; emit("data $reads") } }.collect { seen += it } }
        advanceUntilIdle()

        attempts.retry()
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("data 1", "loading", "data 2"), seen)
    }

    @Test
    fun aScreenThatComesBackReadsAgainWithoutLoading() = runTest {
        val attempts = ReadAttempts()
        var reads = 0
        val flow = attempts.reading({ "loading" }) { flow { reads++; emit("data $reads") } }
        val first = mutableListOf<String>()
        launch { flow.collect { first += it } }.also { advanceUntilIdle() }.cancel()

        val second = mutableListOf<String>()
        launch { flow.collect { second += it } }.also { advanceUntilIdle() }.cancel()

        assertEquals(listOf("data 1"), first)
        assertEquals(listOf("data 2"), second)
    }

    @Test
    fun aRetryWithNoScreenWatchingIsNotReplayedLater() = runTest {
        val attempts = ReadAttempts()
        attempts.retry()
        val seen = mutableListOf<String>()
        val job = launch { attempts.reading({ "loading" }) { flow { emit("data") } }.collect { seen += it } }
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf("data"), seen)
    }
}
