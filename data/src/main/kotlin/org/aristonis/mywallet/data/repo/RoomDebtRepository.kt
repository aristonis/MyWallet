package org.aristonis.mywallet.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.db.escapeLikePrefix
import org.aristonis.mywallet.data.db.toDomain
import org.aristonis.mywallet.data.db.toEntity
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.NameKey
import org.aristonis.mywallet.domain.port.DebtRepository
import java.time.LocalDate

/**
 * Stores debt rows. A person is found by the key of their name, never by the name as typed, so
 * " Ali " and "ALI" land on one debt per side and currency; the unique index on those three columns
 * is the backstop when something gets past the lookups.
 *
 * Every read goes through [toDomain], so a stored row whose name or key is wrong surfaces as
 * [WalletException.UnreadableRow] naming it.
 */
class RoomDebtRepository(private val database: WalletDatabase) : DebtRepository {

    private val dao = database.debtDao()

    override fun observeAll(): Flow<List<Debt>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun findById(id: Long): Debt? = dao.findById(id)?.toDomain()

    /**
     * Builds the debt first, which keys the name, so a blank name fails before any SQL runs. The
     * select and the insert share a transaction (joining the runner's when there is one), and the
     * insert aborts on a clash rather than skipping it.
     *
     * A row already there keeps its stored spelling: it is the same person, and changing how the name
     * is written is what [rename] is for.
     */
    override suspend fun getOrCreate(counterparty: String, side: DebtSide, currencyCode: String): Debt {
        val candidate = Debt(counterparty = counterparty, side = side, currencyCode = currencyCode)
        return database.withTransaction {
            dao.findByKey(side.name, currencyCode, candidate.key.value)?.toDomain()
                ?: candidate.copy(id = dao.insert(candidate.toEntity()))
        }
    }

    /** An unknown [id] changes nothing. */
    override suspend fun updateDetails(id: Long, dueDate: LocalDate?, note: String?) {
        dao.updateDetails(id, dueDate, note)
    }

    /**
     * Refused when another row of the same side and currency already holds the new key, including a
     * row no entry points at any more: it is still that person's debt, and taking its name would
     * leave two debts answering to one name. A rename that changes only the spelling (the same key)
     * passes. An unknown [id] changes nothing.
     *
     * The lookup runs inside the same transaction as the update, so it is the authority on a clash. A
     * constraint error from the unique index would mean the lookup and the index disagree about a
     * key, which is a bug; it propagates as it is rather than being passed off as a taken name.
     *
     * @throws WalletException.NameBlank when [counterparty] is blank, before any SQL runs.
     * @throws WalletException.DebtNameTaken when another debt on that side and currency has the name.
     */
    override suspend fun rename(id: Long, counterparty: String) {
        val key = NameKey.of(counterparty)
        val name = counterparty.trim()
        database.withTransaction { renameUnlessTaken(id, name, key) }
    }

    /** The lookup and the update share one transaction, so no other write can slip in between. */
    private suspend fun renameUnlessTaken(id: Long, name: String, key: NameKey) {
        val row = dao.findById(id) ?: return
        val holder = dao.findByKey(row.side, row.currencyCode, key.value)
        if (holder != null && holder.id != id) throw WalletException.DebtNameTaken(name)
        dao.rename(id, name, key.value)
    }

    /**
     * A blank [prefix] gives every name. Otherwise the prefix is keyed like a name, so " ALI" finds
     * "Alice"; a prefix that keys to nothing (only invisible characters, say) matches no one.
     */
    override suspend fun counterpartyNames(prefix: String): List<String> {
        if (prefix.isBlank()) return dao.counterpartyNames(ALL_NAMES)
        val key = try {
            NameKey.of(prefix)
        } catch (_: WalletException.NameBlank) {
            return emptyList()
        }
        return dao.counterpartyNames(escapeLikePrefix(key.value))
    }

    private companion object {
        /** An empty LIKE prefix: the pattern becomes `%`, which matches every key. */
        const val ALL_NAMES = ""
    }
}
