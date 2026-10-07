package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction

/** Which side of a report an entry lands on. */
enum class EntryKind { INCOME, EXPENSE }

/**
 * A transaction reduced to what a report needs: which side it counts on, the category it is filed
 * under, and its amount. [Transaction.reportEntry] gives the amount as recorded; converting it to the
 * base currency is the report's job.
 */
data class ReportEntry(
    val kind: EntryKind,
    val categoryId: Long,
    val subCategoryId: Long?,
    val amount: Money,
)

/**
 * What this transaction counts as in a report, or null when it is not earning or spending.
 *
 * This is the one place that decides what a report counts. Every report derives both its
 * missing-rate check and its sums from here, and the `when` has no `else` branch on purpose: a new
 * transaction kind fails to compile here, and only here, instead of compiling fine and silently
 * dropping out of every total.
 */
fun Transaction.reportEntry(): ReportEntry? = when (this) {
    is Transaction.Income -> ReportEntry(EntryKind.INCOME, categoryId, subCategoryId, amount)
    is Transaction.Expense -> ReportEntry(EntryKind.EXPENSE, categoryId, subCategoryId, amount)
    // Moving money between the user's own accounts is neither earning nor spending.
    is Transaction.Transfer -> null
}
