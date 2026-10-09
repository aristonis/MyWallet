package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.EntryRecipes
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import java.math.BigDecimal

/**
 * Pure mappers between an entry's stored rows and the domain's [EntryParts]. Amounts and rates are
 * stored as `toPlainString()` text, so their scale survives the round trip.
 *
 * Reading is where stored data can be wrong (a role a newer build wrote, a hand-edited amount), and
 * every such failure surfaces as [WalletException.UnreadableRow] naming the entry, the one error the
 * screens and the restore check know how to show.
 */

/** An entry header with its legs, as read from or written to the tables in one go. */
data class StoredEntry(val entry: EntryEntity, val legs: List<EntryLegEntity>)

/**
 * The entries of a range read, in the order of [EntryRangeRows.headers]. The legs come in the same
 * entry order, so one walk down both lists hands each header its own legs without grouping.
 *
 * @throws WalletException.UnreadableRow for the first entry whose kind or legs cannot be read.
 */
fun EntryRangeRows.toTransactions(): List<Transaction> {
    val transactions = ArrayList<Transaction>(headers.size)
    var next = 0
    for (header in headers) {
        val first = next
        while (next < legs.size && legs[next].entryId == header.id) next++
        val until = next
        transactions += header.read {
            val entryLegs = ArrayList<Leg>(until - first)
            for (index in first until until) entryLegs += legs[index].toLeg()
            entryLegs
        }
    }
    // Both lists come from one snapshot in the same order, so a leg left over is a bug in the read.
    check(next == legs.size) { "leg ${legs[next].legId} of entry ${legs[next].entryId} matches no header" }
    return transactions
}

/**
 * The entries these rows hold, in the order each entry first appears, so the query's order is kept.
 * A row with no leg id is an entry with no legs at all.
 *
 * @throws WalletException.UnreadableRow for the first entry whose kind or legs cannot be read.
 */
fun List<EntryLegRow>.toTransactions(): List<Transaction> =
    groupBy { it.entryId }.values.map { rows ->
        val first = rows.first()
        EntryEntity(first.entryId, first.kind, first.date, first.note, first.pairRate)
            .read { rows.mapNotNull { it.toLegOrNull() } }
    }

/**
 * Role, position, amount and currency are NOT NULL columns, so a leg row that has an id has them.
 * A null there would mean the schema itself is not what this build expects.
 */
private fun EntryLegRow.toLegOrNull(): Leg? {
    val id = legId ?: return null
    val role = checkNotNull(role) { "leg $id has no role" }
    val position = checkNotNull(position) { "leg $id has no position" }
    val amount = checkNotNull(amount) { "leg $id has no amount" }
    val currencyCode = checkNotNull(currencyCode) { "leg $id has no currency" }
    return Leg(
        role = legRole(id, role),
        position = position,
        target = legTarget(id, accountId, debtId, categoryId, subCategoryId, debtSideNamed(debtSide)),
        amount = Money.of(BigDecimal(amount), currencyCode),
        rateToBase = rateToBase?.let(::BigDecimal),
    )
}

/** Throws [IllegalArgumentException] on any value this build cannot read; the caller names the row. */
private fun LegRow.toLeg(): Leg = Leg(
    role = legRole(legId, role),
    position = position,
    target = legTarget(legId, accountId, debtId, categoryId, subCategoryId, debtSideNamed(debtSide)),
    amount = Money.of(BigDecimal(amount), currencyCode),
    rateToBase = rateToBase?.let(::BigDecimal),
)

/**
 * The side read beside a debt leg. A side this build does not know reads as none, so the leg on it
 * reads as a debt with no row and the entry is reported unreadable instead of guessed at.
 */
private fun debtSideNamed(name: String?): DebtSide? = name?.let(DEBT_SIDES_BY_NAME::get)

/**
 * The rows that store these parts. A debt leg writes only its `debtId`: the side belongs to the debt
 * row, so storing it again would let the two disagree.
 */
fun EntryParts.toRows(): StoredEntry = StoredEntry(
    entry = EntryEntity(
        id = header.id,
        kind = header.kind,
        date = header.date,
        note = header.note,
        pairRate = header.pairRate?.toPlainString(),
    ),
    legs = legs.map { it.toRow(entryId = header.id) },
)

/**
 * These rows as domain parts. [debtSides] holds the side of every debt the legs point at; the
 * mapper fills each debt target's side from it.
 *
 * @throws WalletException.UnreadableRow when a role, amount or rate does not parse, a leg is on no
 *   target or on more than one, or a debt leg's debt is missing from [debtSides].
 */
fun StoredEntry.toParts(debtSides: Map<Long, DebtSide>): EntryParts =
    entry.parts { legs.map { it.toLeg(debtSides) } }

/**
 * These rows as the entry they store, read by the recipe for the stored kind.
 *
 * @throws WalletException.UnreadableRow when no recipe reads the kind, the rows do not parse, or the
 *   recipe rejects the legs.
 */
fun StoredEntry.toTransaction(debtSides: Map<Long, DebtSide>): Transaction =
    entry.read { legs.map { it.toLeg(debtSides) } }

/**
 * The entry this header and the legs from [readLegs] store. The recipe is looked up first, so an
 * unknown kind is reported as such before any leg is parsed.
 */
private inline fun EntryEntity.read(readLegs: () -> List<Leg>): Transaction {
    val recipe = EntryRecipes.forKind(kind)
        ?: throw WalletException.UnreadableRow(id, kind, IllegalArgumentException("no recipe reads kind $kind"))
    return recipe.fromLegs(parts(readLegs))
}

/** This header with the legs from [readLegs], any value this build cannot read naming the row. */
private inline fun EntryEntity.parts(readLegs: () -> List<Leg>): EntryParts =
    try {
        EntryParts(
            header = EntryHeader(id = id, kind = kind, date = date, note = note, pairRate = pairRate?.let(::BigDecimal)),
            legs = readLegs(),
        )
    } catch (e: IllegalArgumentException) {
        // Covers NumberFormatException from the decimal parses and every require/enum lookup below.
        throw WalletException.UnreadableRow(id, kind, e)
    }

private fun Leg.toRow(entryId: Long): EntryLegEntity {
    // DEDUCTION legs pair with their deduction lines by (entry, position).
    val base = EntryLegEntity(
        entryId = entryId,
        role = role.name,
        position = position,
        amount = amount.amount.toPlainString(),
        currencyCode = amount.currencyCode,
        rateToBase = rateToBase?.toPlainString(),
    )
    return when (val target = target) {
        is LegTarget.Account -> base.copy(accountId = target.id)
        is LegTarget.Debt -> base.copy(debtId = target.id)
        is LegTarget.Category -> base.copy(categoryId = target.id, subCategoryId = target.subId)
    }
}

/** Throws [IllegalArgumentException] on any value this build cannot read; the caller names the row. */
private fun EntryLegEntity.toLeg(debtSides: Map<Long, DebtSide>): Leg = Leg(
    role = legRole(id, role),
    position = position,
    target = legTarget(id, accountId, debtId, categoryId, subCategoryId, debtId?.let(debtSides::get)),
    amount = Money.of(BigDecimal(amount), currencyCode),
    rateToBase = rateToBase?.let(::BigDecimal),
)

/** Looked up once per leg of every read, so the names are mapped once rather than scanned each time. */
private val LEG_ROLES_BY_NAME: Map<String, LegRole> = LegRole.entries.associateBy { it.name }
private val DEBT_SIDES_BY_NAME: Map<String, DebtSide> = DebtSide.entries.associateBy { it.name }

private fun legRole(legId: Long, name: String): LegRole =
    requireNotNull(LEG_ROLES_BY_NAME[name]) { "leg $legId has unknown role $name" }

/**
 * The one thing the leg is on. [debtSide] is the side of the debt it points at, null when there is
 * no such debt row or the side is one this build does not know.
 */
private fun legTarget(
    legId: Long,
    accountId: Long?,
    debtId: Long?,
    categoryId: Long?,
    subCategoryId: Long?,
    debtSide: DebtSide?,
): LegTarget {
    val targetCount = countOf(accountId) + countOf(debtId) + countOf(categoryId)
    require(targetCount == 1) {
        "leg $legId must be on exactly one target, has account=$accountId debt=$debtId category=$categoryId"
    }
    require(subCategoryId == null || categoryId != null) {
        "leg $legId has sub-category $subCategoryId but no category"
    }
    accountId?.let { return LegTarget.Account(it) }
    debtId?.let { debt ->
        val side = requireNotNull(debtSide) { "leg $legId is on debt $debt, which has no row" }
        return LegTarget.Debt(debt, side)
    }
    return LegTarget.Category(id = requireNotNull(categoryId), subId = subCategoryId)
}

private fun countOf(id: Long?): Int = if (id == null) 0 else 1
