package org.aristonis.mywallet.domain.model

import java.math.BigDecimal

/**
 * Exact-decimal money value object (NFR-2). Amount is a [BigDecimal] — arithmetic never touches
 * floats. Cross-currency conversion is deliberately NOT here (it needs the base currency); see the
 * FX use-cases. Equality is by value and IGNORES scale (2.0 == 2.00).
 */
class Money private constructor(
    val amount: BigDecimal,
    val currencyCode: String,
) : Comparable<Money> {

    val isZero: Boolean get() = amount.signum() == 0
    val isPositive: Boolean get() = amount.signum() > 0
    val isNegative: Boolean get() = amount.signum() < 0

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount.add(other.amount), currencyCode)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount.subtract(other.amount), currencyCode)
    }

    operator fun unaryMinus(): Money = Money(amount.negate(), currencyCode)

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amount.compareTo(other.amount)
    }

    private fun requireSameCurrency(other: Money) {
        require(currencyCode == other.currencyCode) {
            "currency mismatch: $currencyCode vs ${other.currencyCode}"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Money &&
            currencyCode == other.currencyCode &&
            amount.compareTo(other.amount) == 0

    override fun hashCode(): Int = 31 * currencyCode.hashCode() + amount.signum()

    override fun toString(): String = "$amount $currencyCode"

    companion object {
        fun of(amount: BigDecimal, currencyCode: String): Money {
            require(currencyCode.isNotBlank()) { "currencyCode must not be blank" }
            return Money(amount, currencyCode)
        }

        fun of(amount: String, currencyCode: String): Money = of(BigDecimal(amount), currencyCode)

        fun zero(currencyCode: String): Money = of(BigDecimal.ZERO, currencyCode)
    }
}
