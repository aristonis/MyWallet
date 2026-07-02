package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.service.CurrencyConverter
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Moves [amount] from one account to another. Same-currency transfers copy the amount 1:1;
 * cross-currency transfers convert via [org.aristonis.mywallet.domain.service.CurrencyConverter]
 * and store BOTH the source and converted destination amounts + the applied rate (AC-5).
 * Atomicity of the two legs is the repository/data-layer's job (one DB transaction, SG-4).
 */
class RecordTransfer(
    private val accounts: AccountRepository,
    private val currencies: CurrencyRepository,
    private val rates: RateRepository,
    private val settings: SettingsRepository,
    private val transactions: TransactionRepository,
) {
    suspend operator fun invoke(
        sourceAccountId: Long,
        destAccountId: Long,
        amount: Money,
        date: LocalDate,
        note: String? = null,
    ): Long {
        val source = accounts.findById(sourceAccountId)
            ?: throw WalletException.AccountNotFound(sourceAccountId)
        val dest = accounts.findById(destAccountId)
            ?: throw WalletException.AccountNotFound(destAccountId)
        if (amount.currencyCode != source.currencyCode) {
            throw WalletException.CurrencyMismatch(amount.currencyCode, source.currencyCode)
        }

        val destAmount: Money
        val rateUsed: BigDecimal
        if (source.currencyCode == dest.currencyCode) {
            destAmount = amount
            rateUsed = BigDecimal.ONE
        } else {
            val converter = converterFor(setOf(source.currencyCode, dest.currencyCode))
            destAmount = converter.convert(amount, dest.currencyCode)
            rateUsed = converter.sourceToDestRate(source.currencyCode, dest.currencyCode)
        }

        return transactions.add(
            Transaction.Transfer(
                sourceAccountId = sourceAccountId,
                destAccountId = destAccountId,
                sourceAmount = amount,
                destAmount = destAmount,
                rateUsed = rateUsed,
                date = date,
                note = note,
            ),
        )
    }

    /** Loads the rates + decimals for [codes] and builds a pure converter. Fails loud on gaps. */
    private suspend fun converterFor(codes: Set<String>): CurrencyConverter {
        val base = settings.get().baseCurrencyCode
        val ratesToBase = mutableMapOf<String, BigDecimal>()
        val decimals = mutableMapOf<String, Int>()
        for (code in codes) {
            val currency = currencies.findByCode(code)
                ?: throw WalletException.CurrencyNotFound(code)
            decimals[code] = currency.decimalPlaces
            if (code != base) {
                ratesToBase[code] = rates.findByCode(code)?.rateToBase
                    ?: throw WalletException.MissingRate(code)
            }
        }
        return CurrencyConverter(base, ratesToBase, decimals)
    }
}
