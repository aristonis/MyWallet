package org.aristonis.mywallet.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.db.toDomain
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.port.FxRepository

/**
 * Reads the base currency, the rates and the currency list as one consistent snapshot.
 *
 * Room invalidates per table, so observing those three separately means a change to one can arrive
 * before the others. A base-currency change is the case that matters: for one emission the new base
 * is paired with the rates that belonged to the old one, and every converted figure — net worth, the
 * per-account line, each report — renders a wrong number as an ordinary amount, with nothing
 * anywhere saying so.
 *
 * The fix is not to avoid the torn trigger but to make it harmless. The three streams still decide
 * WHEN to re-read; the re-read itself happens inside a single transaction, so whichever table fired
 * first, what comes back is a state that actually existed together. [distinctUntilChanged] then
 * drops the duplicate emissions that a multi-table write naturally produces.
 */
class RoomFxRepository(private val database: WalletDatabase) : FxRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeFx(): Flow<FxSnapshot> =
        combine(
            database.settingsDao().observe(),
            database.rateDao().observeAll(),
            database.currencyDao().observeAll(),
        ) { _, _, _ -> Unit }
            // mapLatest, so a burst of table invalidations from one write collapses into the last
            // re-read rather than queueing a transaction per notification.
            .mapLatest { readSnapshot() }
            .distinctUntilChanged()

    /**
     * Returns null-safe defaults before onboarding has written settings: there is no base currency
     * yet, so there is nothing to convert and every consumer is already showing its empty state.
     */
    private suspend fun readSnapshot(): FxSnapshot = database.withTransaction {
        val settings = database.settingsDao().get()
        FxSnapshot(
            baseCurrencyCode = settings?.baseCurrencyCode.orEmpty(),
            ratesToBase = database.rateDao().getAll().associate { it.currencyCode to it.toDomain().rateToBase },
            currencies = database.currencyDao().getAll().map { it.toDomain() },
        )
    }
}
