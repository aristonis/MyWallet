package org.aristonis.mywallet.domain.model

import org.aristonis.mywallet.domain.error.WalletException
import java.math.BigDecimal

/** Whether an account fee is a percentage of the amount sent or a fixed amount. */
enum class FeeMode { PERCENT, FIXED }

/**
 * Fee calculation settings attached to an account. A fixed [value] is in the account's currency;
 * a percentage is expressed in percent (for example, 2 means 2%). Zero means no fee. A percentage
 * above 100 would charge more than the whole amount sent, so it is refused with
 * [WalletException.FeePercentAboveHundred] wherever a rule is made: the form, a stored row, a restore.
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
        if (mode == FeeMode.PERCENT && value > MAX_PERCENT) throw WalletException.FeePercentAboveHundred(value)
    }

    private companion object {
        const val MAX_STORED_SCALE = 30
        val MAX_PERCENT: BigDecimal = BigDecimal(100)
    }
}
