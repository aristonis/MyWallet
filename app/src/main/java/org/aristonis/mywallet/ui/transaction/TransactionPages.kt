package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.EntryKey

/**
 * The newest [pages] pages of a window as loaded so far, newest first. [hasMore] says the window
 * holds entries past them.
 */
internal data class LoadedPages(
    val transactions: List<Transaction>,
    val pages: Int,
    val hasMore: Boolean,
)

/** One keyset read of a page: entries of [DateRange] strictly after the key, at most the limit, newest first. */
internal typealias PageSource = (range: DateRange, after: EntryKey?, limit: Int) -> Flow<List<Transaction>>

/**
 * Loads a window's entries a page at a time and keeps the loaded pages current. [readPage] says
 * which entries are paged, so the all-entries list and one account's entries share this logic.
 *
 * Three reads, each of [pageSize] plus one entry (the extra one says whether more exist):
 * - the first page is one live read from the newest entry. It stays open, and its answer to every
 *   write is also the signal that the loaded rows may have changed;
 * - a next page is one read strictly after the last loaded entry, appended to the rows, so loading
 *   more never reads the earlier pages again;
 * - after a write, when more than the first page is loaded, every loaded page is read again in one
 *   read from the newest entry. Re-reading them together, rather than page by page from keys taken
 *   before the write, is what keeps the rows an unbroken run: an entry inserted or removed near the
 *   top shifts the rest without a gap or a repeat.
 *
 * Appends and refreshes take turns under one lock, so an append never builds on rows a refresh is
 * replacing. A newer write cancels a refresh still reading. Any failed read fails the flow.
 */
internal class TransactionPages(
    private val readPage: PageSource,
    private val pageSize: Int,
) {

    /**
     * The loaded pages of [range], emitted after the first page arrives and after every append and
     * refresh. [pagesWanted] is how many pages the user has scrolled into; the next pages are loaded
     * as soon as it grows, or as soon as the first page is in when it already asked for more.
     */
    fun observe(range: DateRange, pagesWanted: Flow<Int>): Flow<LoadedPages> = channelFlow {
        val lock = Mutex()
        var loaded: LoadedPages? = null
        var wanted = 1

        /** Appends pages one read at a time until [wanted] are loaded or none are left. */
        suspend fun catchUp() {
            var current = loaded ?: return
            while (current.hasMore && current.pages < wanted) {
                val last = current.transactions.last()
                val next = readPage(range, EntryKey(last.date, last.id), pageSize + 1).first()
                current = LoadedPages(
                    transactions = current.transactions + next.take(pageSize),
                    pages = current.pages + 1,
                    hasMore = next.size > pageSize,
                )
                loaded = current
                send(current)
            }
        }

        launch {
            pagesWanted.collect { pages ->
                lock.withLock {
                    wanted = pages
                    catchUp()
                }
            }
        }

        readPage(range, null, pageSize + 1).collectLatest { firstPage ->
            lock.withLock {
                val pages = loaded?.pages ?: 1
                val limit = pages * pageSize
                val rows = if (pages == 1) firstPage else readPage(range, null, limit + 1).first()
                val refreshed = LoadedPages(rows.take(limit), pages, hasMore = rows.size > limit)
                loaded = refreshed
                send(refreshed)
                catchUp()
            }
        }
    }
}
