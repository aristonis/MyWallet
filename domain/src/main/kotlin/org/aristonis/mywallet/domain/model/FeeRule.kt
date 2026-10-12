package org.aristonis.mywallet.domain.model

import java.math.BigDecimal

/** Whether an account fee is a percentage of the amount sent or a fixed amount. */
enum class FeeMode { PERCENT, FIXED }

/**
 * Fee calculation settings attached to an account. A fixed [value] is in the account's currency;
 * a percentage is expressed in percent (for example, 2 means 2%). Zero means no fee.
 *
 * Values remain exact decimals until [org.aristonis.mywallet.domain.service.FeeCalculator]
 * rounds the result to the sending currency's configured precision.
 */
data class FeeRule(
    val mode: FeeMode,
    val value: BigDecimal,
) {
    init {
        require(value.signum() >= 0) { "fee value must not be negative" }
        val normalizedScale = value.stripTrailingZeros().scale()
        require(normalizedScale in -MAX_STORED_SCALE..MAX_STORED_SCALE) {
            "fee value scale $normalizedScale exceeds the supported range"
        }
    }

    private companion object {
        const val MAX_STORED_SCALE = 30
    }
}
