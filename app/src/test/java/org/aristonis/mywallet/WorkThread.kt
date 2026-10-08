package org.aristonis.mywallet

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A list that remembers which threads walked it. Handed to a view model as data it must read while
 * building its state, it shows where that building ran: the dispatcher alone cannot, because work
 * placed after `flowOn` would still leave the dispatcher busy while the building ran on main.
 */
class ThreadRecordingList<T>(private val inner: List<T>) : List<T> by inner {
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Names of every thread that has iterated this list. */
    val threads: Set<String> get() = seen.toSet()

    override fun iterator(): Iterator<T> {
        // Coroutine debug mode appends " @coroutine#N" to the thread's name while a coroutine runs on it.
        seen += Thread.currentThread().name.substringBefore(" @")
        return inner.iterator()
    }
}

/**
 * One named background thread standing in for the default dispatcher. Leave it running: a view model's
 * sharing coroutine can still post its last clean-up to it after the test reads the state, and a
 * shut-down executor rejects that task, failing whichever test runs next. It is a daemon thread, so it
 * ends with the test run.
 */
fun namedWorkThread(name: String): CoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name).apply { isDaemon = true } }.asCoroutineDispatcher()
