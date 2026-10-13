package org.aristonis.mywallet.data.backup

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Persisted rules must be usable after restore, including disabled rules and historical inputs. */
class BackupStoredValuesTest {
    private val sample = BackupV2Samples.wallet()
    private val item = DeductionItemDto(5, "Tax", "tax", "PERCENT", "10", null, "NOW", 35, true, 0)

    private fun invalid(backup: WalletBackupV2) {
        assertThrows(WalletException.BackupInvalid::class.java) { decodeValidated(BackupCodec.encode(backup)) }
    }

    @Test
    fun deductionAboveOneHundredIsRejected() =
        invalid(sample.copy(deductionItems = listOf(item.copy(value = "100.01"))))
    @Test
    fun zeroDeductionIsRejected() = invalid(sample.copy(deductionItems = listOf(item.copy(value = "0"))))
    @Test
    fun negativeDeductionIsRejected() = invalid(sample.copy(deductionItems = listOf(item.copy(value = "-1"))))
    @Test
    fun malformedDeductionIsRejected() = invalid(sample.copy(deductionItems = listOf(item.copy(value = "1,5"))))
    @Test
    fun unknownDeductionModeIsRejected() = invalid(sample.copy(deductionItems = listOf(item.copy(mode = "ODD"))))
    @Test
    fun unknownTimingIsRejected() = invalid(sample.copy(deductionItems = listOf(item.copy(timing = "ODD"))))
    @Test
    fun percentageWithCurrencyIsRejected() =
        invalid(sample.copy(deductionItems = listOf(item.copy(valueCurrencyCode = "USD"))))
    @Test
    fun fixedWithoutCurrencyIsRejected() = invalid(sample.copy(deductionItems = listOf(item.copy(mode = "FIXED"))))
    @Test
    fun disabledInvalidDeductionIsRejected() =
        invalid(sample.copy(deductionItems = listOf(item.copy(enabled = false, value = "101"))))

    @Test fun exactHundredPercentAndFixedAboveHundredAreAccepted() {
        val items = listOf(
            item.copy(value = "100.000"),
            item.copy(id = 6, name = "Bills", nameKey = "bills", mode = "FIXED", value = "1000.123",
                valueCurrencyCode = "EUR", timing = "LATER"),
        )
        val file = sample.copy(deductionItems = items)
        assertEquals(file, decodeValidated(BackupCodec.encode(file)))
    }

    private fun withFee(mode: String?, value: String?) = sample.copy(accounts = sample.accounts.map {
        if (it.id == 1L) it.copy(defaultFeeMode = mode, defaultFeeValue = value) else it
    })

    @Test fun storedPercentFeeAboveHundredIsRejected() = invalid(withFee("PERCENT", "100.01"))
    @Test fun fixedFeeAboveHundredIsAccepted() {
        val file = withFee("FIXED", "1000.123")
        assertEquals(file, decodeValidated(BackupCodec.encode(file)))
    }
    @Test fun storedPercentFeeAtHundredIsAccepted() {
        val file = withFee("PERCENT", "100.000")
        assertEquals(file, decodeValidated(BackupCodec.encode(file)))
    }
    @Test fun storedNegativeFeeIsRejected() = invalid(withFee("FIXED", "-1"))
    @Test fun storedFeeWithMissingModeIsRejected() = invalid(withFee(null, "2"))
    @Test fun storedFeeWithMissingValueIsRejected() = invalid(withFee("PERCENT", null))
    @Test fun storedFeeWithUnknownModeIsRejected() = invalid(withFee("ODD", "2"))
    @Test fun storedFeeWithMalformedValueIsRejected() = invalid(withFee("FIXED", "no"))
    private fun withHistoricalValue(value: String): WalletBackupV2 = sample.copy(
        deductionItems = listOf(item),
        deductionLines = listOf(DeductionLineDto(1, 1, 5, 0, "Earlier tax", "PERCENT", value, null, "NOW")),
        legs = sample.legs.map {
            if (it.entryId == 1L && it.role == "ACCOUNT") it.copy(amount = "1110.60") else it
        } + LegDto(1, "DEDUCTION", 0, null, null, 35, null, "123.40", "USD", "1"),
    )

    @Test fun historicalPercentAboveHundredIsRejected() = invalid(withHistoricalValue("100.01"))
    @Test fun historicalZeroValueIsRejected() = invalid(withHistoricalValue("0"))
    @Test fun historicalMalformedValueIsRejected() = invalid(withHistoricalValue("bad"))
    @Test fun historicalPercentAndCurrentRuleKeepTheirIndependentInputs() {
        val file = withHistoricalValue("10.000")
        assertEquals(file, decodeValidated(BackupCodec.encode(file)))
    }
}
