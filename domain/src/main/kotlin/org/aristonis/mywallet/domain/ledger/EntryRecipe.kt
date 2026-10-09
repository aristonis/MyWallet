package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.model.Transaction

/**
 * Turns one kind of [Transaction] into stored legs and back. Every kind registers one, so the rest
 * of the app works with entries and never with legs, and every recipe is held to the same contract
 * through its [examples].
 */
interface EntryRecipe<K : Transaction> {

    /** The kind stored on the header. Stored as text, so it never changes once released. */
    val kind: String

    /** The header and signed legs that store [entry]. */
    fun toLegs(entry: K): EntryParts

    /**
     * Reads stored [parts] back into an entry.
     *
     * @throws org.aristonis.mywallet.domain.error.WalletException.UnreadableRow when the roles,
     *   targets, amounts or values are not what this kind writes.
     */
    fun fromLegs(parts: EntryParts): K

    /** How each category leg role of [entry] counts in Tracking. Every category leg needs a rule. */
    fun trackingRules(entry: K): Map<LegRole, TrackingRule>

    /**
     * True only for a kind that records a balance that existed before the app. Such an entry changes
     * what is owed without any income or expense behind it, so the balance check in the recipe
     * contract skips it; every other rule still applies.
     */
    val opensBalances: Boolean get() = false

    /** Sample entries the contract test checks; at least one in the base currency and one foreign. */
    fun examples(): List<K>
}
