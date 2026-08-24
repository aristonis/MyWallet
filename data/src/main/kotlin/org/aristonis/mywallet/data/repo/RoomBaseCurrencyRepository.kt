package org.aristonis.mywallet.data.repo

import androidx.room.withTransaction
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.db.toEntity
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.port.BaseCurrencyRepository

/**
 * Applies a base-currency change as one all-or-nothing write.
 *
 * Two things have to be true together or the wallet is left quietly wrong. The rates must be
 * REPLACED, not merged: a code absent from the new set is a code with no rate, and that is what
 * removes the row belonging to the currency now becoming the base. Merging would leave that row
 * behind where nothing can see it — the converter short-circuits on the base code before it looks
 * anything up — until the next change read it as real data and inflated every figure in that
 * currency by the round-trip factor.
 *
 * And the settings row is re-read and copied HERE, inside the transaction, rather than being handed
 * in from outside. An aggregate read before the write carries every other field with it, so a theme
 * change landing in between would be silently rolled back along with it.
 */
class RoomBaseCurrencyRepository(private val database: WalletDatabase) : BaseCurrencyRepository {

    override suspend fun rebase(newBaseCurrencyCode: String, rates: List<ExchangeRate>) {
        database.withTransaction {
            val settings = database.settingsDao().get()
                ?: error("cannot change the base currency before onboarding has set one")

            database.rateDao().clear()
            database.rateDao().insertAll(rates.map { it.toEntity() })
            database.settingsDao().upsert(settings.copy(baseCurrencyCode = newBaseCurrencyCode))
        }
    }
}
