package org.aristonis.mywallet.data.repo

import androidx.room.withTransaction
import org.aristonis.mywallet.data.db.CategoryLegRateRow
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.db.toEntity
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.port.BaseCurrencyRepository
import org.aristonis.mywallet.domain.port.LegRateChange
import java.math.BigDecimal

/**
 * Applies a base-currency change as one all-or-nothing write.
 *
 * Three things have to be true together or the wallet is left quietly wrong. The rates must be
 * REPLACED, not merged: a code absent from the new set is a code with no rate, and that is what
 * removes the row belonging to the currency now becoming the base. Merging would leave that row
 * behind where nothing can see it — the converter short-circuits on the base code before it looks
 * anything up — until the next change read it as real data and inflated every figure in that
 * currency by the round-trip factor.
 *
 * The rates on category legs move in the same transaction. Left behind, every past month would be
 * counted against a base that is no longer the base, and shown as an ordinary total.
 *
 * And the settings row is re-read and copied HERE, inside the transaction, rather than being handed
 * in from outside. An aggregate read before the write carries every other field with it, so a theme
 * change landing in between would be silently rolled back along with it.
 */
class RoomBaseCurrencyRepository(private val database: WalletDatabase) : BaseCurrencyRepository {

    override suspend fun rebase(
        newBaseCurrencyCode: String,
        rates: List<ExchangeRate>,
        legRates: List<LegRateChange>,
    ) {
        database.withTransaction {
            val settings = database.settingsDao().get()
                ?: error("cannot change the base currency before onboarding has set one")

            rewriteLegRates(legRates)
            database.rateDao().clear()
            database.rateDao().insertAll(rates.map { it.toEntity() })
            database.settingsDao().upsert(settings.copy(baseCurrencyCode = newBaseCurrencyCode))
        }
    }

    /**
     * Decides each leg's new rate from the rate it holds now, then writes by leg id. Deciding first
     * is what keeps this a single pass: written change by change, a leg moved from 0.0025 to 0.002
     * would be moved again by a change that starts from 0.002.
     */
    private suspend fun rewriteLegRates(changes: List<LegRateChange>) {
        if (changes.isEmpty()) return
        val dao = database.ledgerQueryDao()
        val byCurrency = changes.groupBy { it.currencyCode }
        // Many legs share one stored text, so each distinct (currency, text) is matched only once.
        // A null target (the leg keeps its rate) is remembered too, which getOrPut would not do.
        val decided = HashMap<Pair<String, String?>, String?>()
        dao.categoryLegRates()
            .mapNotNull { leg ->
                val key = leg.currencyCode to leg.rateToBase
                val target = if (key in decided) decided[key] else targetOf(leg, byCurrency).also { decided[key] = it }
                target?.let { leg.id to it }
            }
            .groupBy(keySelector = { it.second }, valueTransform = { it.first })
            .forEach { (rate, ids) -> ids.chunked(MAX_IDS_PER_UPDATE).forEach { dao.setLegRate(it, rate) } }
    }

    /** The text [leg] must hold after the change, or null when no change names its rate. */
    private fun targetOf(leg: CategoryLegRateRow, byCurrency: Map<String, List<LegRateChange>>): String? {
        val stored = leg.rateToBase?.let(::BigDecimal)
        val change = byCurrency[leg.currencyCode]?.firstOrNull { it.from.sameRateAs(stored) } ?: return null
        return change.to.toPlainString()
    }

    /** Compared by value, so `1.10` matches `1.1`; null matches only null. */
    private fun BigDecimal?.sameRateAs(other: BigDecimal?): Boolean =
        if (this == null || other == null) this == null && other == null else compareTo(other) == 0

    private companion object {
        /** Ids per UPDATE, under SQLite's default limit of 999 bound values (one more is the rate). */
        const val MAX_IDS_PER_UPDATE = 900
    }
}
