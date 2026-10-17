package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.EntryRecipes
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.TrackingRule
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.TransactionFilter
import org.aristonis.mywallet.domain.model.TypeGroup

/** Shares stored-leg matching between saved-entry visibility and the in-memory repository. */
object TransactionFilterMatch {
    fun matches(entry: Transaction, filter: TransactionFilter): Boolean = matches(entry.toParts(), filter)

    fun matches(parts: EntryParts, filter: TransactionFilter): Boolean =
        (filter.types.isEmpty() || typeGroup(parts) in filter.types) &&
            (filter.accountIds.isEmpty() || parts.legs.any { (it.target as? LegTarget.Account)?.id in filter.accountIds }) &&
            (filter.categoryIds.isEmpty() || categoryLegs(parts, filter.categoryIds).isNotEmpty())

    fun matchedCategoryLegs(entry: Transaction, categoryIds: Set<Long>): List<Leg> =
        categoryLegs(entry.toParts(), categoryIds)

    private fun categoryLegs(parts: EntryParts, categoryIds: Set<Long>): List<Leg> = parts.legs.filter { leg ->
        val category = leg.target as? LegTarget.Category
        val rule = EntryRecipes.trackingRule(parts.header.kind, leg.role)
        category != null && (category.id in categoryIds || category.subId in categoryIds) &&
            rule != null && rule != TrackingRule.NOT_COUNTED
    }

    fun typeGroup(entry: Transaction): TypeGroup = when (entry) {
        is Transaction.Income, is Transaction.EarnedUnpaid -> TypeGroup.INCOME
        is Transaction.Expense, is Transaction.Refund, is Transaction.CreditPurchase -> TypeGroup.EXPENSE
        is Transaction.Transfer -> TypeGroup.TRANSFER
        is Transaction.Borrow, is Transaction.Lend, is Transaction.OwedBefore, is Transaction.Repayment -> TypeGroup.DEBTS
        is Transaction.WriteOff -> when (entry.side) {
            DebtSide.OWED_TO_ME -> TypeGroup.EXPENSE
            DebtSide.I_OWE -> TypeGroup.INCOME
        }
    }

    /** Unknown kinds stay unknown so All can still surface them through the repository's decoder. */
    fun typeGroup(parts: EntryParts): TypeGroup? = when (parts.header.kind) {
        "INCOME", "EARNED_UNPAID" -> TypeGroup.INCOME
        "EXPENSE", "REFUND", "CREDIT_PURCHASE" -> TypeGroup.EXPENSE
        "TRANSFER" -> TypeGroup.TRANSFER
        "BORROW", "LEND", "OWED_BEFORE", "REPAYMENT" -> TypeGroup.DEBTS
        "WRITE_OFF" -> when {
            parts.legs.any { it.role == LegRole.LOSS && it.target is LegTarget.Category } -> TypeGroup.EXPENSE
            parts.legs.any { it.role == LegRole.FORGIVEN && it.target is LegTarget.Category } -> TypeGroup.INCOME
            else -> null
        }
        else -> null
    }
}
