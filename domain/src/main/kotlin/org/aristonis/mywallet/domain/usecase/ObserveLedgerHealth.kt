package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.EntryRecipes
import org.aristonis.mywallet.domain.port.LedgerQueries

/**
 * Whether every stored entry can be read. Screens that sum projections (balances, Tracking) never
 * open whole entries, so without this an entry of a kind no recipe reads would be left out of the
 * numbers with nothing to show for it. A screen combines this with its own read and shows the
 * "cannot be read" message instead of totals that quietly miss a row.
 *
 * Emits Unit while every entry's kind has a recipe, and fails with [WalletException.UnreadableRow]
 * naming the first entry, by id, whose kind has none. A row the upgrade could not convert is stored
 * under a kind no recipe registers, so it is reported here too.
 *
 * A debt whose remaining is below zero is not checked here. It can be repaired by editing or deleting
 * the entry that overpaid it, and a failure here would lock every screen that reads health and leave
 * no way to reach that entry. The Home summary and the debts screen report it instead.
 */
class ObserveLedgerHealth(private val ledger: LedgerQueries) {

    operator fun invoke(): Flow<Unit> = ledger.observeUnknownKind(KNOWN_KINDS).map { unknown ->
        if (unknown != null) throw WalletException.UnreadableRow(unknown.id, unknown.kind)
    }

    private companion object {
        val KNOWN_KINDS: Set<String> = EntryRecipes.all.map { it.kind }.toSet()
    }
}
