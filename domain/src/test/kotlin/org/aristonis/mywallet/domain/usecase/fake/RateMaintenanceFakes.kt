package org.aristonis.mywallet.domain.usecase.fake

import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.port.BaseCurrencyRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.LegRateChange
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigDecimal

/**
 * Assumed port shape used by these fakes:
 * `data class LegRateChange(val currencyCode: String, val from: BigDecimal?, val to: BigDecimal)` in
 * `domain.port`, meaning "every category leg in [currencyCode] whose stored rate equals [from]
 * (compared by value; null matches a leg with no rate) now stores [to]".
 * `BaseCurrencyRepository.rebase(newBaseCurrencyCode, rates, legRates: List<LegRateChange>)`.
 */

/**
 * A [BaseCurrencyRepository] that applies the leg changes to the legs held by [entries], the way the
 * real one updates `entry_legs`. Each leg is rewritten at most once, by the first change that matches
 * it, so a change cannot chain onto the result of another. Rates are recorded like the plain fake.
 */
class LegApplyingBaseCurrencyRepository(private val entries: FakeEntryRepository) : BaseCurrencyRepository {

    var lastRates: List<ExchangeRate>? = null
        private set
    var rebaseCount: Int = 0
        private set

    override suspend fun rebase(
        newBaseCurrencyCode: String,
        rates: List<ExchangeRate>,
        legRates: List<LegRateChange>,
    ) {
        rebaseCount++
        lastRates = rates
        entries.updateLegs { leg ->
            val change = legRates.firstOrNull { it.currencyCode == leg.amount.currencyCode && matches(it.from, leg.rateToBase) }
            if (leg.target is LegTarget.Category && change != null) leg.copy(rateToBase = change.to) else leg
        }
    }

    private fun matches(from: BigDecimal?, stored: BigDecimal?): Boolean =
        if (from == null || stored == null) from == null && stored == null else from.compareTo(stored) == 0
}

/** A [LedgerQueries] that records every [fillMissingRate] call and passes everything on to [delegate]. */
class RecordingLedgerQueries(private val delegate: LedgerQueries) : LedgerQueries by delegate {

    /** Each fill asked for, in order, as (currency code, rate). */
    val fills = mutableListOf<Pair<String, BigDecimal>>()

    override suspend fun fillMissingRate(code: String, rate: BigDecimal): Int {
        fills += code to rate
        return delegate.fillMissingRate(code, rate)
    }
}

/**
 * A [LedgerQueries] that answers only `isAccountInUse` (true for ids in [accountsInUse]) and fails
 * any other member, so a test proves a use case asked the one question it needs and read no list.
 * Built on a proxy so it keeps working when the port gains reads.
 */
class InUseOnlyLedgerQueries private constructor(
    private val accountsInUse: Set<Long>,
    private val askedLog: MutableList<Long>,
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        check(method.name == IN_USE) { "only isAccountInUse may be asked here, but ${method.name} was called" }
        // A suspend function arrives as (accountId, continuation); the proxy answers synchronously.
        val id = args!![0] as Long
        askedLog += id
        return id in accountsInUse
    }

    companion object {
        private const val IN_USE = "isAccountInUse"

        /** The ledger and, as the second value, the account ids it was asked about in order. */
        fun of(accountsInUse: Set<Long> = emptySet()): Pair<LedgerQueries, List<Long>> {
            val asked = mutableListOf<Long>()
            val handler = InUseOnlyLedgerQueries(accountsInUse, asked)
            val ledger = Proxy.newProxyInstance(
                LedgerQueries::class.java.classLoader,
                arrayOf(LedgerQueries::class.java),
                handler,
            ) as LedgerQueries
            return ledger to asked
        }
    }
}
