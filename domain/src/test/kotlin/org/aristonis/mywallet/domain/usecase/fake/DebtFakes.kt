package org.aristonis.mywallet.domain.usecase.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.NameKey
import org.aristonis.mywallet.domain.port.DebtRepository
import java.time.LocalDate

/**
 * In-memory [DebtRepository]. A debt is found by (name key, side, currency), so "ali" and " Ali "
 * reach the same row, as the real table's unique key does.
 */
class FakeDebtRepository(initial: List<Debt> = emptyList()) : DebtRepository {
    private val items = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    /** Everything currently stored, for assertions. */
    val stored: List<Debt> get() = items.value

    override fun observeAll(): Flow<List<Debt>> = items

    override suspend fun findById(id: Long): Debt? = items.value.firstOrNull { it.id == id }

    override suspend fun getOrCreate(counterparty: String, side: DebtSide, currencyCode: String): Debt {
        val key = NameKey.of(counterparty)
        val existing = items.value.firstOrNull {
            it.key == key && it.side == side && it.currencyCode == currencyCode
        }
        if (existing != null) return existing
        val created = Debt(id = nextId++, counterparty = counterparty, side = side, currencyCode = currencyCode)
        items.value = items.value + created
        return created
    }

    override suspend fun updateDetails(id: Long, dueDate: LocalDate?, note: String?) {
        items.value = items.value.map { if (it.id == id) it.copy(dueDate = dueDate, note = note) else it }
    }

    override suspend fun rename(id: Long, counterparty: String) {
        items.value = items.value.map { if (it.id == id) it.copy(counterparty = counterparty) else it }
    }

    override suspend fun counterpartyNames(prefix: String): List<String> {
        val start = if (prefix.isBlank()) "" else NameKey.of(prefix).value
        return items.value
            .filter { it.key.value.startsWith(start) }
            .distinctBy { it.key }
            .sortedBy { it.key.value }
            .map { it.counterparty }
    }
}
