package org.aristonis.mywallet.domain.port

import kotlinx.coroutines.flow.Flow
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import java.time.LocalDate

/**
 * Stores debt rows: who, which way, which currency, plus a due date and a note. It never stores an
 * amount; what remains on a debt is summed from its legs.
 */
interface DebtRepository {

    /** Every debt row, including ones no entry points at any more. */
    fun observeAll(): Flow<List<Debt>>

    /** The debt with [id], or null when there is none. */
    suspend fun findById(id: Long): Debt?

    /**
     * The debt for this person, side and currency, created when there is none yet. People match by
     * their [org.aristonis.mywallet.domain.model.NameKey], so " Ali " finds "ali". Runs inside the
     * runner transaction.
     *
     * Implementations must look the debt up first and insert only when nothing matched, with an
     * insert that fails on a clash rather than one that silently skips it, so a clash is never hidden.
     *
     * @throws org.aristonis.mywallet.domain.error.WalletException.NameBlank when [counterparty] is blank.
     */
    suspend fun getOrCreate(counterparty: String, side: DebtSide, currencyCode: String): Debt

    /** Replaces the due date and note of debt [id]; null clears either. */
    suspend fun updateDetails(id: Long, dueDate: LocalDate?, note: String?)

    /**
     * Changes the name shown for debt [id]; its key follows the new name.
     *
     * @throws org.aristonis.mywallet.domain.error.WalletException.NameBlank when [counterparty] is blank.
     */
    suspend fun rename(id: Long, counterparty: String)

    /** Names as typed whose key starts with [prefix]'s key, each once; a blank prefix gives all. */
    suspend fun counterpartyNames(prefix: String): List<String>
}
