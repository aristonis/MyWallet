package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * Transfers written by builds 1.0 to 1.1.1 are read as stored. Those builds priced the destination
 * through the base currency at full precision and stored the pair rate rounded to 12 places, so
 * re-pricing the destination from the stored rate can differ by a minor unit from what is stored.
 * Reading checks structure only; the save path prices.
 */
class TransferStoredShapesTest : DebtRecipeFixtures() {

    private fun eur(a: String) = org.aristonis.mywallet.domain.model.Money.of(a, "EUR")

    /**
     * Base SYP, EUR 14321.75, USD 13250.5. 1.1.1 converted 265.01 EUR through the base at full
     * precision: 265.01 * 14321.75 / 13250.5 = 286.435 -> 286.44. It stored the pair rate
     * 14321.75 / 13250.5 rounded to 12 places: 1.080846005811, which re-prices to 286.43.
     */
    @Test
    fun aTransferStoredByVersion111ReadsBackAsStored() {
        val stored = EntryParts(
            header = EntryHeader(60, "TRANSFER", day, null, pairRate = BigDecimal("1.080846005811")),
            legs = listOf(
                Leg(LegRole.SOURCE, 0, LegTarget.Account(1), eur("-265.01")),
                Leg(LegRole.DEST, 0, LegTarget.Account(2), usd("286.44")),
            ),
        )

        val transfer = read(stored) as Transaction.Transfer

        assertEquals(usd("286.44"), transfer.destAmount)
        assertEquals(eur("265.01"), transfer.sourceAmount)
        assertEquals(BigDecimal("1.080846005811"), transfer.rateUsed)
    }

    @Test
    fun aTransferStoredByVersion111WithAFeeReadsBackAsStored() {
        val stored = EntryParts(
            header = EntryHeader(61, "TRANSFER", day, null, pairRate = BigDecimal("1.080846005811")),
            legs = listOf(
                Leg(LegRole.SOURCE, 0, LegTarget.Account(1), eur("-270.01")),
                Leg(LegRole.DEST, 0, LegTarget.Account(2), usd("286.44")),
                category(LegRole.FEE, 70, null, eur("5"), BigDecimal("14321.75")),
            ),
        )

        val transfer = read(stored) as Transaction.Transfer

        assertEquals(usd("286.44"), transfer.destAmount)
        assertEquals(BigDecimal("14321.75"), transfer.feeRateToBase)
    }

    @Test
    fun aTypedAmountBeyondTheCurrencyDecimalsReadsBackAsTyped() {
        val transfer = Transaction.Transfer(
            id = 62, sourceAccountId = 1, destAccountId = 3, sourceAmount = usd("10.555"),
            destAmount = usd("10.555"), rateUsed = BigDecimal.ONE, date = day,
        )

        assertRoundTrip(transfer)
    }

    /** 1.1.1 copied a same-currency amount unchanged (dest = source, rate exactly 1), never rounding it. */
    @Test
    fun aSameCurrencyTransferStoredByVersion111KeepsDestEqualToSource() {
        val stored = EntryParts(
            header = EntryHeader(64, "TRANSFER", day, null, pairRate = BigDecimal("1")),
            legs = listOf(
                Leg(LegRole.SOURCE, 0, LegTarget.Account(1), usd("-10.555")),
                Leg(LegRole.DEST, 0, LegTarget.Account(3), usd("10.555")),
            ),
        )

        assertEquals(usd("10.555"), (read(stored) as Transaction.Transfer).destAmount)
        assertUnreadable(stored.copy(legs = stored.legs.map { if (it.role == LegRole.DEST) it.copy(amount = usd("10.56")) else it }))
    }

    @Test
    fun aTransferWithoutAPositivePairRateIsStillUnreadable() {
        val parts = Transaction.Transfer(
            id = 63, sourceAccountId = 1, destAccountId = 2, sourceAmount = usd("10"),
            destAmount = eur("9"), rateUsed = BigDecimal("0.9"), date = day,
        ).toParts()

        assertUnreadable(parts.copy(header = parts.header.copy(pairRate = BigDecimal.ZERO)))
        assertUnreadable(parts.copy(header = parts.header.copy(pairRate = null)))
    }
}
