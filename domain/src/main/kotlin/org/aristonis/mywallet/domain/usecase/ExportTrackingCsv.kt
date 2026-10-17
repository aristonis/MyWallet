package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.ledger.trackingAmount
import org.aristonis.mywallet.domain.ledger.trackingSide
import org.aristonis.mywallet.domain.model.CsvRow
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CsvCategoryLeg
import org.aristonis.mywallet.domain.port.CsvEncoder
import org.aristonis.mywallet.domain.port.CsvExportSnapshot
import org.aristonis.mywallet.domain.port.CsvExportSource
import org.aristonis.mywallet.domain.service.CurrencyConverter
import java.math.BigDecimal

/** Exports exactly the category legs Tracking counts, refusing missing rates before encoding anything. */
class ExportTrackingCsv(private val source: CsvExportSource, private val encoder: CsvEncoder) {
    suspend operator fun invoke(range: DateRange): String {
        val snapshot = source.read(range)
        // Validate every side first so an unreadable row is never hidden by a missing rate.
        snapshot.legs.forEach { it.leg.trackingSide() }
        val base = snapshot.fx.baseCurrencyCode
        val missing = snapshot.legs.filter { it.leg.amount.currencyCode != base && it.leg.rateToBase == null }
            .maxWithOrNull(compareBy<CsvCategoryLeg> { it.leg.date }.thenBy { it.leg.entryId })
        if (missing != null) throw WalletException.MissingRate(missing.leg.amount.currencyCode)
        val context = RowContext(snapshot)
        return encoder.encode(snapshot.legs.filterNot { it.leg.amount.isZero }.sortedWith(
            compareByDescending<CsvCategoryLeg> { it.leg.date }.thenByDescending { it.leg.entryId }
                .thenBy { it.leg.role.ordinal }.thenBy { it.position },
        ).map { row(it, context) })
    }

    private fun row(csvLeg: CsvCategoryLeg, context: RowContext): CsvRow {
        val leg = csvLeg.leg
        val snapshot = context.snapshot
        val entry = context.entries[leg.entryId]
            ?: throw WalletException.UnreadableRow(leg.entryId, leg.entryKind)
        val parts = checkNotNull(context.parts[leg.entryId])
        val accountId = parts.legs.mapNotNull { (it.target as? LegTarget.Account)?.id }.firstOrNull()
        val base = snapshot.fx.baseCurrencyCode
        val decimals = snapshot.fx.decimalPlaces[base] ?: CurrencyConverter.DEFAULT_DECIMALS
        return CsvRow(
            leg.date, leg.entryId, leg.entryKind, leg.role.name,
            context.categories[leg.categoryId]?.name ?: leg.categoryId.toString(),
            leg.subCategoryId?.let { id -> context.categories[id]?.name ?: id.toString() } ?: "",
            leg.trackingSide(), leg.amount.amount, leg.amount.currencyCode,
            if (leg.amount.currencyCode == base) BigDecimal.ONE else checkNotNull(leg.rateToBase),
            leg.trackingAmount(base, decimals).amount,
            accountId?.let { id -> context.accounts[id]?.name ?: id.toString() } ?: "",
            counterparty(csvLeg, entry, context),
            entry.note ?: "",
        )
    }
    private fun counterparty(csvLeg: CsvCategoryLeg, entry: Transaction, context: RowContext): String {
        if (entry is Transaction.Income) {
            if (csvLeg.leg.role != LegRole.DEDUCTION) return ""
            val line = context.deductions[entry.id]?.get(csvLeg.position)
                ?: throw WalletException.UnreadableRow(entry.id, csvLeg.leg.entryKind)
            return line.debtId?.let { context.debts[it]?.counterparty } ?: line.name
        }
        if (entry is Transaction.Expense && csvLeg.leg.role != LegRole.PAID_LATER) return ""
        val debtId = context.parts[entry.id]?.legs
            ?.mapNotNull { (it.target as? LegTarget.Debt)?.id }?.firstOrNull()
        return debtId?.let { context.debts[it]?.counterparty ?: it.toString() } ?: ""
    }

    private class RowContext(val snapshot: CsvExportSnapshot) {
        val entries = snapshot.entries.associateBy { it.id }
        val parts = snapshot.entries.associate { it.id to it.toParts() }
        val deductions = snapshot.entries.filterIsInstance<Transaction.Income>()
            .associate { it.id to it.deductions.associateBy { line -> line.position } }
        val categories = snapshot.categories.associateBy { it.id }
        val accounts = snapshot.accounts.associateBy { it.id }
        val debts = snapshot.debts.associateBy { it.id }
    }
}
