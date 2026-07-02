package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.PeriodSummary
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
 * Live income / expense / net over a period (FR-17), in the base currency. Transfers are excluded
 * (internal movement, not earning/spending). Opening balances are not transactions, so also excluded.
 */
class ComputePeriodSummary(
    private val transactions: TransactionRepository,
    private val currencies: CurrencyRepository,
    private val rates: RateRepository,
    private val settings: SettingsRepository,
) {
    operator fun invoke(period: TrackingPeriod, reference: LocalDate): Flow<PeriodSummary> =
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

            var income = Money.zero(base)
            var expense = Money.zero(base)
            for (tx in txs) {
                if (tx.date !in range) continue
                when (tx) {
                    is Transaction.Income -> income += converter.convert(tx.amount, base)
                    is Transaction.Expense -> expense += converter.convert(tx.amount, base)
                    is Transaction.Transfer -> {} // internal movement — excluded from income/expense
                }
            }
            PeriodSummary(income = income, expense = expense, net = income - expense)
        }
}
