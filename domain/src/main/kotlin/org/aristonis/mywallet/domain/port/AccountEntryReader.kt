package org.aristonis.mywallet.domain.port

import kotlinx.coroutines.flow.Flow
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Transaction

/**
 * Reads the entries of one account for its detail page. Kept apart from [EntryRepository] so the
 * list and edit flows, and every fake of them, stay as they are.
 */
interface AccountEntryReader {

    /**
     * At most [limit] complete entries that have a leg on [accountId], dated inside the inclusive
     * [range], newest first (date desc, then id desc), starting strictly after the entry [after]
     * names (null starts at the newest). The account filter runs in storage, so an older match is
     * never hidden behind a page of other accounts' entries.
     *
     * An entry that touches the account on more than one leg appears once. Each entry carries all of
     * its legs, not only the one on [accountId]. Re-emits whenever an entry changes. A row no recipe
     * can read fails the flow with
     * [org.aristonis.mywallet.domain.error.WalletException.UnreadableRow].
     */
    fun observeAccountPage(
        accountId: Long,
        range: DateRange,
        after: EntryKey?,
        limit: Int,
    ): Flow<List<Transaction>>
}
