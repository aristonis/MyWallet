package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The save prices a transfer itself, so what it writes always reads back. Builds up to 1.1.1 let the
 * owner type amounts beyond a currency's decimals, and a fee in the sending currency needs that
 * currency's rate on its own leg even when it is not the base.
 */
class SaveTransferStoredShapesTest {

    private val currencies = listOf(Currency("USD", "$", 2), Currency("SYP", "£S", 0), Currency("EUR", "€", 2))
    private val secondSyp = EntryWorld.account(4, "SYP")
    private val accounts = listOf(EntryWorld.usdAccount, EntryWorld.sypAccount, EntryWorld.secondUsdAccount, secondSyp)

    private fun rig(vararg rates: Pair<String, String>) =
        SaveEntryRig(accounts = accounts, savedRates = rates.toMap(), currencies = currencies)

    private fun transfer(
        from: Long, to: Long, amount: Money, fee: Money? = null, feeCategoryId: Long? = null,
    ) = Transaction.Transfer(
        sourceAccountId = from, destAccountId = to, sourceAmount = amount, destAmount = amount,
        rateUsed = BigDecimal.ONE, date = EntryWorld.day, fee = fee, feeCategoryId = feeCategoryId,
    )

    @Test
    fun aUsdToUsdTransferOfTenPointFiveFiveFiveSavesAndReadsBack() = runTest {
        val rig = rig()

        val saved = rig.saveEntry(transfer(1, 3, Money.of("10.555", "USD")), typedRates = emptyMap())

        val stored = rig.entries.findById(saved.id) as Transaction.Transfer
        assertEquals(Money.of("10.555", "USD"), stored.destAmount)
    }

    @Test
    fun aTypedSypTransferOfFifteenHundredPointFiveSavesAndReadsBack() = runTest {
        val rig = rig()

        val saved = rig.saveEntry(transfer(2, 4, Money.of("1500.5", "SYP")), typedRates = emptyMap())

        val stored = rig.entries.findById(saved.id) as Transaction.Transfer
        assertEquals(Money.of("1500.5", "SYP"), stored.destAmount)
    }

    @Test
    fun aSypFeeUnderAUsdBaseStoresTheSypRateOnTheFeeLeg() = runTest {
        val rig = rig("SYP" to "0.0001")

        val saved = rig.saveEntry(
            transfer(2, 1, Money.of("100000", "SYP"), fee = Money.of("6000", "SYP"), feeCategoryId = 6),
            typedRates = emptyMap(),
        )

        val feeLeg = rig.entries.rows.value.single().legs.single { it.role == LegRole.FEE }
        assertEquals(0, BigDecimal("0.0001").compareTo(feeLeg.rateToBase))
        val stored = rig.entries.findById(saved.id) as Transaction.Transfer
        assertEquals(0, BigDecimal("0.0001").compareTo(stored.feeRateToBase))
    }

    @Test
    fun theFeeOfASypTransferIsCountedInThePeriodBreakdownWithoutAMissingRate() = runTest {
        val rig = rig("SYP" to "0.0001")
        rig.saveEntry(
            transfer(2, 1, Money.of("100000", "SYP"), fee = Money.of("6000", "SYP"), feeCategoryId = 6),
            typedRates = emptyMap(),
        )
        val fx = fakeFx(currencies, rig.rates.observeAll().first(), "USD")

        val result = ComputeCategoryBreakdown(FakeLedgerQueries(rig.entries), fx)(
            DateRange(EntryWorld.day, EntryWorld.day),
        ).first()

        assertTrue("expected a resolved breakdown but was $result", result is CategoryBreakdownResult.Resolved)
        assertEquals(Money.of("0.60", "USD"), (result as CategoryBreakdownResult.Resolved).breakdown.summary.expense)
    }
}
