package org.aristonis.mywallet.domain.model

import java.time.LocalDate

/**
 * Money owed between the owner and one other person, in one currency and running one way ([side]).
 * The debt holds no amount: what remains is the sum of its legs, so it can never disagree with the
 * entries that moved it.
 *
 * One person is one debt per side and currency. [key] is what makes two typed names the same person;
 * [counterparty] keeps the name as typed for display, with the ends trimmed and the inner spacing
 * left alone.
 *
 * This is a plain class rather than a data class because a data class stores its constructor
 * arguments untouched, and its generated `copy` would let an untrimmed name in on a rename. Equality,
 * hashing and [copy] are written out below and behave as a data class's would.
 *
 * @throws org.aristonis.mywallet.domain.error.WalletException.NameBlank when [counterparty] is blank.
 */
class Debt(
    val id: Long = 0,
    counterparty: String,
    val side: DebtSide,
    val currencyCode: String,
    val dueDate: LocalDate? = null,
    val note: String? = null,
) {
    val counterparty: String = counterparty.trim()

    /** Derived from the name, so a rename through [copy] always moves the key with it. */
    val key: NameKey = NameKey.of(counterparty)

    init {
        require(currencyCode.isNotBlank()) { "debt currencyCode must not be blank" }
    }

    /** A changed copy; a new [counterparty] is trimmed and re-keyed like the original. */
    fun copy(
        id: Long = this.id,
        counterparty: String = this.counterparty,
        side: DebtSide = this.side,
        currencyCode: String = this.currencyCode,
        dueDate: LocalDate? = this.dueDate,
        note: String? = this.note,
    ): Debt = Debt(id, counterparty, side, currencyCode, dueDate, note)

    /** [key] is left out: it follows from [counterparty]. */
    override fun equals(other: Any?): Boolean =
        this === other || other is Debt &&
            id == other.id &&
            counterparty == other.counterparty &&
            side == other.side &&
            currencyCode == other.currencyCode &&
            dueDate == other.dueDate &&
            note == other.note

    override fun hashCode(): Int = listOf(id, counterparty, side, currencyCode, dueDate, note).hashCode()

    override fun toString(): String =
        "Debt(id=$id, counterparty=$counterparty, side=$side, currencyCode=$currencyCode, " +
            "dueDate=$dueDate, note=$note)"
}
