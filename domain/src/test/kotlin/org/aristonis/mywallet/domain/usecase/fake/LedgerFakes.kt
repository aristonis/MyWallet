package org.aristonis.mywallet.domain.usecase.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.EntryRecipes
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.port.CategoryLeg
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.EntryKey
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.LegRate
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import java.math.BigDecimal

/**
 * In-memory [EntryRepository] that stores what the real one stores: a header plus legs per entry,
 * written and read through the recipes. Tests therefore see the same round trip, the same order and
 * the same [WalletException.UnreadableRow] for a row no recipe can read.
 */
class FakeEntryRepository(initial: List<Transaction> = emptyList()) : EntryRepository {

    private val store = MutableStateFlow(initial.map { it.toParts() })
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    /** Every stored header and its legs, for [FakeLedgerQueries] and for assertions. */
    val rows: StateFlow<List<EntryParts>> = store.asStateFlow()

    /** Stores [parts] as they are, e.g. a row of an unknown kind or with broken legs. */
    fun seedRaw(parts: EntryParts) {
        store.value = store.value.filterNot { it.header.id == parts.header.id } + parts
        nextId = maxOf(nextId, parts.header.id + 1)
    }

    /** Rewrites every stored leg through [transform] in one step, as a single SQL update would. */
    fun updateLegs(transform: (Leg) -> Leg) {
        store.value = store.value.map { row -> row.copy(legs = row.legs.map(transform)) }
    }

    override fun observeOnDebt(debtId: Long): Flow<List<Transaction>> = store.map { rows ->
        rows.filter { row -> row.legs.any { (it.target as? LegTarget.Debt)?.id == debtId } }
            .sortedWith(NEWEST_FIRST).map(::read)
    }

    override suspend fun entryIdsOnDebt(debtId: Long, excludingKinds: Set<String>): List<Long> =
        store.value.filter { row ->
            row.header.kind !in excludingKinds && row.legs.any { (it.target as? LegTarget.Debt)?.id == debtId }
        }.sortedWith(NEWEST_FIRST).map { it.header.id }

    override fun observeAll(): Flow<List<Transaction>> = observeBetween(DateRange.ALL_TIME)

    override fun observeBetween(range: DateRange): Flow<List<Transaction>> = store.map { rows ->
        rows.filter { it.header.date in range }.sortedWith(NEWEST_FIRST).map(::read)
    }

    override fun observePage(range: DateRange, after: EntryKey?, limit: Int): Flow<List<Transaction>> =
        store.map { rows ->
            rows.filter { it.header.date in range }
                .sortedWith(NEWEST_FIRST)
                .filter { after == null || it.header.isBefore(after) }
                .take(limit)
                .map(::read)
        }

    override suspend fun findById(id: Long): Transaction? =
        store.value.firstOrNull { it.header.id == id }?.let(::read)

    override suspend fun insert(entry: Transaction): Long {
        val id = if (entry.id == 0L) nextId else entry.id
        // Mirrors the plain INSERT: a taken id aborts rather than overwriting another entry.
        if (store.value.any { it.header.id == id }) throw WalletException.EntryIdTaken(id)
        val parts = entry.toParts()
        store.value = store.value + parts.copy(header = parts.header.copy(id = id))
        nextId = maxOf(nextId, id + 1)
        return id
    }

    override suspend fun replace(entry: Transaction) {
        check(store.value.any { it.header.id == entry.id }) { "entry ${entry.id} does not exist" }
        store.value = store.value.map { if (it.header.id == entry.id) entry.toParts() else it }
    }

    override suspend fun delete(id: Long) {
        store.value = store.value.filterNot { it.header.id == id }
    }

    private fun read(parts: EntryParts): Transaction {
        val recipe = EntryRecipes.forKind(parts.header.kind)
            ?: throw WalletException.UnreadableRow(parts.header.id, parts.header.kind)
        return recipe.fromLegs(parts)
    }

    /** Strictly after [key] in the list's order: an older date, or the same date with a lower id. */
    private fun EntryHeader.isBefore(key: EntryKey): Boolean =
        date < key.date || (date == key.date && id < key.id)

    private companion object {
        val NEWEST_FIRST: Comparator<EntryParts> =
            compareByDescending<EntryParts> { it.header.date }.thenByDescending { it.header.id }
    }
}

/**
 * In-memory [LedgerQueries] projecting the legs held by [entries], so balances, Tracking and guards
 * in use-case tests read the same legs the recipes wrote.
 */
class FakeLedgerQueries(private val entries: FakeEntryRepository) : LedgerQueries {

    override suspend fun debtLegs(debtIds: Set<Long>): List<DebtLeg> = entries.rows.value.flatMap { row ->
        row.legs.mapNotNull { leg ->
            (leg.target as? LegTarget.Debt)?.takeIf { it.id in debtIds }?.let { DebtLeg(it.id, it.side, leg.amount) }
        }
    }

    override fun observeAccountLegs(): Flow<List<AccountLeg>> = entries.rows.map { rows ->
        rows.flatMap { row ->
            row.legs.mapNotNull { leg ->
                (leg.target as? LegTarget.Account)?.let { AccountLeg(it.id, leg.amount) }
            }
        }
    }

    override fun observeDebtLegs(): Flow<List<DebtLeg>> = entries.rows.map { rows ->
        rows.flatMap { row ->
            row.legs.mapNotNull { leg ->
                (leg.target as? LegTarget.Debt)?.let { DebtLeg(it.id, it.side, leg.amount) }
            }
        }
    }

    override fun observeCategoryLegs(range: DateRange): Flow<List<CategoryLeg>> = entries.rows.map { rows ->
        rows.filter { it.header.date in range }.flatMap { row ->
            row.legs.mapNotNull { leg ->
                val target = leg.target as? LegTarget.Category ?: return@mapNotNull null
                CategoryLeg(
                    entryId = row.header.id,
                    date = row.header.date,
                    categoryId = target.id,
                    subCategoryId = target.subId,
                    entryKind = row.header.kind,
                    role = leg.role,
                    amount = leg.amount,
                    rateToBase = leg.rateToBase,
                )
            }
        }
    }

    override fun observeUnknownKind(known: Set<String>): Flow<UnreadableRowRef?> = entries.rows.map { rows ->
        rows.sortedBy { it.header.id }
            .firstOrNull { it.header.kind !in known }
            ?.let { UnreadableRowRef(it.header.id, it.header.kind) }
    }

    override suspend fun isAccountInUse(accountId: Long): Boolean =
        entries.rows.value.any { row -> row.legs.any { it.target == LegTarget.Account(accountId) } }

    override fun observeAccountsInUse(): Flow<Set<Long>> = entries.rows.map { rows ->
        rows.flatMap { row -> row.legs.mapNotNull { (it.target as? LegTarget.Account)?.id } }.toSet()
    }

    override fun observeCategoryUsage(): Flow<Map<Long, Int>> = entries.rows.map { rows ->
        rows.flatMap { row -> categoryIdsOf(row) }.groupingBy { it }.eachCount()
    }

    override suspend fun fillMissingRate(code: String, rate: BigDecimal): Int {
        var changed = 0
        entries.updateLegs { leg ->
            if (leg.target is LegTarget.Category && leg.rateToBase == null && leg.amount.currencyCode == code) {
                changed++
                leg.copy(rateToBase = rate)
            } else {
                leg
            }
        }
        return changed
    }

    override suspend fun distinctLegRates(): List<LegRate> = entries.rows.value
        .flatMap { it.legs }
        .filter { it.target is LegTarget.Category }
        .map { LegRate(it.amount.currencyCode, it.rateToBase) }
        .distinct()

    /** Each category id one entry uses, counted once per entry even when several legs share it. */
    private fun categoryIdsOf(row: EntryParts): Set<Long> = row.legs
        .mapNotNull { it.target as? LegTarget.Category }
        .flatMap { listOfNotNull(it.id, it.subId) }
        .toSet()
}
