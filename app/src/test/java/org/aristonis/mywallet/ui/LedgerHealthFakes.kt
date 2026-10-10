package org.aristonis.mywallet.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.port.CategoryLeg
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.LegRate
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import java.math.BigDecimal

/**
 * A ledger whose health, category usage and accounts in use a test sets directly. It holds no legs:
 * the screens under test read their own data from other fakes, and this one only answers "is every
 * stored kind readable", "how many entries use each category" and "which accounts are in use".
 */
internal open class HealthLedger : LedgerQueries {

    /** The first stored entry of a kind no recipe reads; null while the ledger is healthy. */
    val unknownKind = MutableStateFlow<UnreadableRowRef?>(null)

    /** Entries per category id, as the usage read reports them. */
    val usage = MutableStateFlow<Map<Long, Int>>(emptyMap())

    private val inUse = MutableStateFlow<Set<Long>>(emptySet())

    /** Accounts that have legs. Setting it re-emits to every in-use reader, as a write to the legs would. */
    var accountsInUse: Set<Long>
        get() = inUse.value
        set(value) {
            inUse.value = value
        }

    /** When set, every health read fails with it, as a broken disk would, instead of answering. */
    var healthFailure: Throwable? = null

    /** Health reads ever started; a retry that reads again counts again. */
    var healthReads = 0
        private set

    /** Usage reads ever started. */
    var usageReads = 0
        private set

    /** The debt legs the ledger holds; empty unless a test sets them. */
    val debtLegs = MutableStateFlow<List<DebtLeg>>(emptyList())

    override fun observeAccountLegs(): Flow<List<AccountLeg>> = flowOf(emptyList())
    override suspend fun debtLegs(debtIds: Set<Long>): List<DebtLeg> = emptyList()
    override fun observeDebtLegs(): Flow<List<DebtLeg>> = debtLegs
    override fun observeCategoryLegs(range: DateRange): Flow<List<CategoryLeg>> = flowOf(emptyList())
    override fun observeUnknownKind(known: Set<String>): Flow<UnreadableRowRef?> =
        unknownKind.onStart {
            healthReads++
            healthFailure?.let { throw it }
        }
    override suspend fun isAccountInUse(accountId: Long): Boolean = accountId in accountsInUse
    override fun observeAccountsInUse(): Flow<Set<Long>> = inUse
    override fun observeCategoryUsage(): Flow<Map<Long, Int>> = usage.onStart { usageReads++ }
    override suspend fun fillMissingRate(code: String, rate: BigDecimal): Int = 0
    override suspend fun distinctLegRates(): List<LegRate> = emptyList()

    /** The real health guard over this ledger. */
    fun health() = ObserveLedgerHealth(this)

    /** An entry of a kind the app has no recipe for appears in the ledger. */
    fun plantUnknownKind(id: Long = 7, kind: String = "FROM_THE_FUTURE") {
        unknownKind.value = UnreadableRowRef(id, kind)
    }

    fun heal() {
        unknownKind.value = null
    }
}

/**
 * A [HealthLedger] whose health read answers again on every [write], as the real one does on every
 * table change, even when the answer is the same. A plain state flow would swallow the repeat.
 */
internal class WriteTickingLedger : HealthLedger() {

    private val answers = MutableSharedFlow<UnreadableRowRef?>(replay = 1, extraBufferCapacity = 16)

    init {
        answers.tryEmit(null)
    }

    /** A write somewhere in the database: the health read answers once more with what it holds now. */
    fun write() {
        answers.tryEmit(unknownKind.value)
    }

    override fun observeUnknownKind(known: Set<String>): Flow<UnreadableRowRef?> = answers
}
