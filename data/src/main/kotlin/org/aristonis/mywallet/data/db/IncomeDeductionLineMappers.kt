package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import java.math.BigDecimal

/** Stores deduction input and identity beside the journal lines, linked by their position. */
fun DeductionLine.toEntity(entryId: Long): IncomeDeductionLineEntity = IncomeDeductionLineEntity(
    entryId = entryId,
    itemId = itemId,
    position = position,
    name = name,
    mode = mode.name,
    value = value.toPlainString(),
    valueCurrencyCode = valueCurrencyCode,
    timing = timing.name,
)

/**
 * Rebuilds the input snapshot with its category and optional debt leg. A rounded-zero amount still
 * has its category leg, preserving the selected rule even though no money or obligation moves.
 */
fun List<IncomeDeductionLineEntity>.toSnapshots(parts: EntryParts): List<DeductionLine> {
    val categoryByPosition = parts.legs.filter { it.role == LegRole.DEDUCTION }.associateBy { it.position }
    val debtByPosition = parts.legs.filter { it.role == LegRole.DEDUCTION_DEBT }.associateBy { it.position }
    return map { row ->
        require(row.itemId > 0 && row.position >= 0 && row.name.isNotBlank()) {
            "deduction line ${row.id} has invalid identity or name"
        }
        val category = requireNotNull(categoryByPosition[row.position]) {
            "deduction line ${row.id} has no category leg"
        }
        val target = requireNotNull(category.target as? LegTarget.Category) {
            "deduction line ${row.id} category leg has no category target"
        }
        val debt = debtByPosition[row.position]?.let { leg ->
            val debtTarget = requireNotNull(leg.target as? LegTarget.Debt) {
                "deduction line ${row.id} debt leg has no debt target"
            }
            require(debtTarget.side == DebtSide.I_OWE) {
                "deduction line ${row.id} debt leg is not an I-owe leg"
            }
            debtTarget.id
        }
        val mode = DeductionMode.valueOf(row.mode)
        val value = BigDecimal(row.value)
        require(value.signum() > 0 && (mode != DeductionMode.PERCENT || value <= BigDecimal("100"))) {
            "deduction line ${row.id} has an invalid value"
        }
        require((mode == DeductionMode.FIXED) == (row.valueCurrencyCode != null)) {
            "deduction line ${row.id} has an invalid value currency"
        }
        require(target.id > 0 && !category.amount.isNegative) {
            "deduction line ${row.id} has an invalid category or amount"
        }
        require(debt == null || debt > 0) { "deduction line ${row.id} has an invalid debt id" }
        DeductionLine(
            itemId = row.itemId,
            position = row.position,
            name = row.name,
            mode = mode,
            value = value,
            valueCurrencyCode = row.valueCurrencyCode,
            timing = DeductionTiming.valueOf(row.timing),
            categoryId = target.id,
            subCategoryId = target.subId,
            amount = category.amount,
            debtId = debt,
        )
    }.sortedBy { it.position }
}
