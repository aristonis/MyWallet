package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.CategoryTotal
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.PeriodRanges
import java.time.LocalDate

/**
 * Live per-category expense totals within a period (FR-18), in the base currency. Same grouping
 * pattern extends to account / sub-category breakdowns when the UI needs them.
 */
class ComputeCategoryBreakdown(
    private val transactions: TransactionRepository,
    private val currencies: CurrencyRepository,
    private val rates: RateRepository,
    private val settings: SettingsRepository,
) {
    operator fun invoke(period: TrackingPeriod, reference: LocalDate): Flow<List<CategoryTotal>> =
        combine(
            transactions.observeAll(),
            currencies.observeAll(),
            rates.observeAll(),
            settings.observe(),
        ) { txs, currencyList, rateList, settingsValue ->
            val base = settingsValue.baseCurrencyCode
            val converter = CurrencyConverter(
                baseCurrencyCode = base,
                ratesToBase = rateList.associate { it.currencyCode to it.rateToBase },
                decimalPlaces = currencyList.associate { it.code to it.decimalPlaces },
            )
            val range = PeriodRanges.of(period, reference)

            txs.asSequence()
                .filter { it.date in range }
                .filterIsInstance<Transaction.Expense>()
                .groupBy { it.categoryId }
                .map { (categoryId, expenses) ->
                    val total = expenses.fold(Money.zero(base)) { acc, e -> acc + converter.convert(e.amount, base) }
                    CategoryTotal(categoryId, total)
                }
        }
}
