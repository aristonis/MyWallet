package org.aristonis.mywallet.data.repo

import androidx.room.withTransaction
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.db.toDomain
import org.aristonis.mywallet.data.db.toTransactions
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.EntryRecipes
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.port.CsvCategoryLeg
import org.aristonis.mywallet.domain.port.CsvExportSnapshot
import org.aristonis.mywallet.domain.port.CsvExportSource
import org.aristonis.mywallet.domain.port.FxRepository

/** Reads export legs, headers, referenced labels and FX from a single database snapshot. */
class RoomCsvExportSource(private val database: WalletDatabase, private val fx: FxRepository) : CsvExportSource {
    override suspend fun read(range: DateRange): CsvExportSnapshot = database.withTransaction {
        database.ledgerQueryDao().firstUnknownKind(EntryRecipes.all.map { it.kind })?.let { unreadable ->
            throw WalletException.UnreadableRow(unreadable.id, unreadable.kind)
        }
        val rows = if (range.isAllTime) database.ledgerQueryDao().readAllCategoryLegs()
            else database.ledgerQueryDao().readCategoryLegs(range.start, range.endInclusive)
        val legs = rows.map { CsvCategoryLeg(it.toCategoryLeg(), it.position) }
        val entries = database.entryDao().readBetween(range.start, range.endInclusive).toTransactions()
        val targets = entries.flatMap { it.toParts().legs }.map { it.target }
        val categoryIds = legs.flatMap { listOfNotNull(it.leg.categoryId, it.leg.subCategoryId) }.toSet()
        val accountIds = targets.filterIsInstance<LegTarget.Account>().map { it.id }.toSet()
        val debtIds = targets.filterIsInstance<LegTarget.Debt>().map { it.id }.toSet()
        CsvExportSnapshot(
            legs, entries, fx.currentFx(),
            categoryIds.mapNotNull { database.categoryDao().findById(it)?.toDomain() },
            accountIds.mapNotNull { database.accountDao().findById(it)?.toDomain() },
            debtIds.mapNotNull { database.debtDao().findById(it)?.toDomain() },
        )
    }
}
