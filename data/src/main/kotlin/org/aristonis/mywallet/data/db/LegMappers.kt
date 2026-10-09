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
    try {
        EntryParts(
            header = EntryHeader(
                id = entry.id,
                kind = entry.kind,
                date = entry.date,
                note = entry.note,
                pairRate = entry.pairRate?.let(::BigDecimal),
            ),
            legs = legs.map { it.toLeg(debtSides) },
        )
    } catch (e: IllegalArgumentException) {
        // Covers NumberFormatException from the decimal parses and every require/enum lookup below.
        throw WalletException.UnreadableRow(entry.id, entry.kind, e)
    }

/**
 * These rows as the entry they store, read by the recipe for the stored kind.
 *
 * @throws WalletException.UnreadableRow when no recipe reads the kind, the rows do not parse, or the
 *   recipe rejects the legs.
 */
fun StoredEntry.toTransaction(debtSides: Map<Long, DebtSide>): Transaction {
    val recipe = EntryRecipes.forKind(entry.kind)
        ?: throw WalletException.UnreadableRow(
            entry.id,
            entry.kind,
            IllegalArgumentException("no recipe reads kind ${entry.kind}"),
        )
    return recipe.fromLegs(toParts(debtSides))
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
    role = requireNotNull(LegRole.entries.firstOrNull { it.name == role }) { "leg $id has unknown role $role" },
    position = position,
    target = target(debtSides),
    amount = Money.of(BigDecimal(amount), currencyCode),
    rateToBase = rateToBase?.let(::BigDecimal),
)

private fun EntryLegEntity.target(debtSides: Map<Long, DebtSide>): LegTarget {
    val targets = listOfNotNull(accountId, debtId, categoryId)
    require(targets.size == 1) {
        "leg $id must be on exactly one target, has account=$accountId debt=$debtId category=$categoryId"
    }
    require(subCategoryId == null || categoryId != null) {
        "leg $id has sub-category $subCategoryId but no category"
    }
    accountId?.let { return LegTarget.Account(it) }
    debtId?.let { debt ->
        val side = requireNotNull(debtSides[debt]) { "leg $id is on debt $debt, which has no row" }
        return LegTarget.Debt(debt, side)
    }
    return LegTarget.Category(id = requireNotNull(categoryId), subId = subCategoryId)
}
