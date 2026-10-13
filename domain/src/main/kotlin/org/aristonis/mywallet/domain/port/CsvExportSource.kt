package org.aristonis.mywallet.domain.port

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Transaction

/** Reads one consistent export snapshot without collecting live flows inside a transaction. */
interface CsvExportSource {
    suspend fun read(range: DateRange): CsvExportSnapshot
}

/** Category legs and their referenced labels read together with the base-currency context. */
data class CsvExportSnapshot(
    val legs: List<CsvCategoryLeg>,
    val entries: List<Transaction>,
    val fx: FxSnapshot,
    val categories: List<Category>,
    val accounts: List<Account>,
    val debts: List<Debt>,
)

/** A Tracking category leg with its stored position, distinguishing equal-valued deduction lines. */
data class CsvCategoryLeg(val leg: CategoryLeg, val position: Int)
