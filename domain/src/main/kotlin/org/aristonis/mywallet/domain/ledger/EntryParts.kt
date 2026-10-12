package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.model.DeductionLine
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The stored header of an entry: what every kind has, whatever its legs.
 *
 * @property kind The recipe that reads the legs back; an unknown kind is reported, never guessed.
 * @property pairRate The rate between the two currencies of a cross-currency entry (a transfer).
 */
data class EntryHeader(
    val id: Long,
    val kind: String,
    val date: LocalDate,
    val note: String?,
    val pairRate: BigDecimal? = null,
)

/** An entry as storage sees it: its header plus its signed legs. Recipes convert to and from it. */
data class EntryParts(
    val header: EntryHeader,
    val legs: List<Leg>,
    /** Income deductions retain their editable and debt-link snapshots beside the journal legs. */
    val deductionLines: List<DeductionLine> = emptyList(),
)
