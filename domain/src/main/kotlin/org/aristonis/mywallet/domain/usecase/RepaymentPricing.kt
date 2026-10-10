package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.RateBook
import java.math.BigDecimal

internal data class RepaymentAmounts(val accountPrincipal: Money, val pairRate: BigDecimal?)

/** Prices only the account principal; interest is added afterward and never changes the debt. */
internal class RepaymentPricing(private val snapshot: FxSnapshot, typedRates: Map<String, BigDecimal>) {
    init {
        typedRates.keys.firstOrNull { it !in snapshot.decimalPlaces }
            ?.let { throw WalletException.CurrencyNotFound(it) }
    }

    private val book = RateBook(snapshot, typedRates)

    fun price(
        principal: Money,
        accountCurrency: String,
        suppliedAccountPrincipal: Money?,
        suppliedPairRate: BigDecimal?,
        old: Transaction.Repayment?,
    ): RepaymentAmounts {
        requireCurrency(principal.currencyCode)
        requireCurrency(accountCurrency)
        require(suppliedPairRate == null || suppliedPairRate.signum() > 0) { "pair rate must be positive" }
        // A third value could only disagree with the other two; the stored rate is reused on later edits.
        require(suppliedAccountPrincipal == null || suppliedPairRate == null) {
            "supply the account principal or the pair rate, not both"
        }
        requireAccountPrincipal(suppliedAccountPrincipal, principal, accountCurrency)
        if (principal.currencyCode == accountCurrency) {
            require(suppliedAccountPrincipal == null || suppliedAccountPrincipal == principal) {
                "same-currency account principal must equal debt principal"
            }
            return RepaymentAmounts(principal, null)
        }
        val samePair = old?.takeIf {
            it.accountAmount.currencyCode == accountCurrency && it.principal.currencyCode == principal.currencyCode
        }
        // A rounded full payoff need not multiply back to its exact debt principal at the stored rate.
        if (suppliedPairRate == null && samePair != null && samePair.principal == principal &&
            (suppliedAccountPrincipal == null || suppliedAccountPrincipal == samePair.accountPrincipal())
        ) {
            return RepaymentAmounts(samePair.accountPrincipal(), samePair.pairRate)
        }
        val rate = suppliedPairRate ?: rateFor(principal, accountCurrency, suppliedAccountPrincipal, samePair)
        if (rate.signum() <= 0) throw WalletException.RateUnderflow(principal.currencyCode)
        val accountPrincipal = suppliedAccountPrincipal ?: Money.of(
            principal.amount.divide(rate, requireCurrency(accountCurrency), CurrencyConverter.ROUNDING), accountCurrency,
        )
        if (principal.isPositive && accountPrincipal.isZero) throw WalletException.AmountRoundsToZero(accountCurrency)
        return RepaymentAmounts(accountPrincipal, rate)
    }

    private fun rateFor(
        principal: Money,
        accountCurrency: String,
        suppliedAccountPrincipal: Money?,
        old: Transaction.Repayment?,
    ): BigDecimal {
        if (suppliedAccountPrincipal?.isPositive == true) {
            return principal.amount.divide(
                suppliedAccountPrincipal.amount, CurrencyConverter.RATE_SCALE, CurrencyConverter.ROUNDING,
            )
        }
        return old?.pairRate ?: book.converter().sourceToDestRate(accountCurrency, principal.currencyCode)
    }

    private fun requireCurrency(code: String): Int =
        snapshot.decimalPlaces[code] ?: throw WalletException.CurrencyNotFound(code)

    private fun requireAccountPrincipal(account: Money?, principal: Money, accountCurrency: String) {
        if (account == null) return
        if (account.currencyCode != accountCurrency) {
            throw WalletException.CurrencyMismatch(account.currencyCode, accountCurrency)
        }
        require(!account.isNegative) { "account principal must not be negative" }
        require(!principal.isZero || account.isZero) { "interest-only repayment cannot move account principal" }
        require(!principal.isPositive || account.isPositive) { "positive debt principal needs positive account principal" }
    }

    private fun Transaction.Repayment.accountPrincipal(): Money = interest?.let { accountAmount - it } ?: accountAmount
}
