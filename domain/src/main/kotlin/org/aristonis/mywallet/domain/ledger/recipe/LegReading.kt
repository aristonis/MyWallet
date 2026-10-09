package org.aristonis.mywallet.domain.ledger.recipe

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import java.util.EnumMap
import java.util.EnumSet

/**
 * The legs one kind of entry stores, by role.
 *
 * @property required Roles present exactly once, at position 0.
 * @property optional Roles present at most once, at position 0 (a fee of zero is simply not stored).
 * @property repeated Roles present any number of times at positions 0, 1, 2 with no gap and no
 *   duplicate, one per line they belong to. A gap or a duplicate is a lost or doubled leg.
 * @property perLine Roles that belong to some deduction lines but not to every line (a debt leg exists
 *   only for a line owed later). Each sits at its line's position, so positions may skip, but they
 *   are distinct and never negative; a duplicate or negative position is unreadable.
 */
internal data class LegShape(
    val required: Set<LegRole>,
    val optional: Set<LegRole> = emptySet(),
    val repeated: Set<LegRole> = emptySet(),
    val perLine: Set<LegRole> = emptySet(),
) {
    private val allowed: Set<LegRole> = required + optional + repeated + perLine

    // Enum sets of the same roles, for the per-entry checks of [groupOncePerRole]: a bit test each.
    private val allowedRoles: EnumSet<LegRole> = enumSetOf(allowed)
    private val requiredRoles: EnumSet<LegRole> = enumSetOf(required)

    /** True when every role this shape allows occurs at most once, so [groupOncePerRole] applies. */
    private val isOncePerRole: Boolean = repeated.isEmpty() && perLine.isEmpty()

    init {
        check(allowed.size == required.size + optional.size + repeated.size + perLine.size) {
            "a role is listed twice in $this"
        }
    }

    /**
     * Groups [legs] by role, each group sorted by position, and fails with an
     * [IllegalArgumentException] on any leg this shape does not allow. Absent optional, repeated and
     * per-line roles are left out of the map rather than mapped to an empty list.
     *
     * Every entry of every read passes through here, so the common case (each role at most once) is
     * checked in one pass without sorting. Anything that pass does not accept goes through the full
     * check below, so a damaged entry fails with exactly the same message either way.
     */
    fun group(legs: List<Leg>): Map<LegRole, List<Leg>> =
        (if (isOncePerRole) groupOncePerRole(legs) else null) ?: groupAnyShape(legs)

    /**
     * The legs keyed by role when each is an allowed role at position 0, no role comes twice and
     * every required role is there; null otherwise, without saying why.
     */
    private fun groupOncePerRole(legs: List<Leg>): Map<LegRole, List<Leg>>? {
        val byRole = EnumMap<LegRole, List<Leg>>(LegRole::class.java)
        var requiredSeen = 0
        for (leg in legs) {
            val role = leg.role
            if (leg.position != 0 || role !in allowedRoles) return null
            if (byRole.put(role, listOf(leg)) != null) return null
            if (role in requiredRoles) requiredSeen++
        }
        return if (requiredSeen == requiredRoles.size) byRole else null
    }

    private fun groupAnyShape(legs: List<Leg>): Map<LegRole, List<Leg>> {
        val byRole = legs.groupBy { it.role }.mapValues { (_, group) -> group.sortedBy { it.position } }
        val unknown = byRole.keys - allowed
        require(unknown.isEmpty()) { "roles $unknown are not part of this kind" }
        val missing = required - byRole.keys
        require(missing.isEmpty()) { "required roles $missing are missing" }
        byRole.forEach { (role, group) -> requireReadablePositions(role, group.map { it.position }) }
        return byRole
    }

    /** [positions] arrive sorted, so a duplicate shows up as two equal neighbours. */
    private fun requireReadablePositions(role: LegRole, positions: List<Int>) {
        if (role in perLine) {
            require(positions.first() >= 0) { "$role legs sit at positions $positions, one is negative" }
            require(positions.zipWithNext().all { (a, b) -> a < b }) { "$role legs share a position in $positions" }
            return
        }
        val expected = if (role in repeated) positions.indices.toList() else listOf(0)
        require(positions == expected) { "$role legs sit at positions $positions, expected $expected" }
    }
}

private fun enumSetOf(roles: Set<LegRole>): EnumSet<LegRole> =
    EnumSet.noneOf(LegRole::class.java).apply { addAll(roles) }

/**
 * Checks that [parts] is a [kind] entry whose legs fit [shape], then hands them to [read] keyed by
 * role, each list sorted by position.
 *
 * An [IllegalArgumentException] inside (a failed `require` or `requireNotNull`, an unparsable
 * number, an entry or [org.aristonis.mywallet.domain.model.Money] whose own checks reject the
 * values) means bad stored data, so it is rethrown as [WalletException.UnreadableRow] naming the
 * row. That keeps the read side down to one error type the screens know how to show. Any other
 * exception is a bug in the recipe and propagates as it is, so it is never passed off as bad data.
 */
internal fun <T> readParts(
    parts: EntryParts,
    kind: String,
    shape: LegShape,
    read: (Map<LegRole, List<Leg>>) -> T,
): T {
    val header = parts.header
    return try {
        require(header.kind == kind) { "header kind ${header.kind} is not $kind" }
        val byRole = shape.group(parts.legs)
        parts.legs.forEach { it.requireReadableRate() }
        read(byRole)
    } catch (e: IllegalArgumentException) {
        throw WalletException.UnreadableRow(header.id, header.kind, e)
    }
}

/** The account this leg is on; fails when the leg targets something else. */
internal fun Leg.accountId(): Long =
    requireNotNull(target as? LegTarget.Account) { "$role leg is on $target, not an account" }.id

/** The category this leg is on; fails when the leg targets something else. */
internal fun Leg.categoryTarget(): LegTarget.Category =
    requireNotNull(target as? LegTarget.Category) { "$role leg is on $target, not a category" }

/** Only category legs are converted for Tracking, so a rate anywhere else was not written by a recipe. */
private fun Leg.requireReadableRate() {
    val rate = rateToBase ?: return
    require(target is LegTarget.Category) { "$role leg on $target carries a rate" }
    require(rate.signum() > 0) { "$role leg has a rate of $rate" }
}
