package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * A new transfer is worked out by the save from the source amount and the rates, the way recording a
 * transfer always was. The destination amount and rate the form sends are placeholders and never
 * reach the books.
 */
class SaveEntryNewTransferTest {

    private val eurAccount = EntryWorld.account(4, "EUR")
    private val allAccounts =
        listOf(EntryWorld.usdAccount, EntryWorld.sypAccount, EntryWorld.secondUsdAccount, eurAccount)

    private suspend fun SaveEntryRig.storedTransfer(id: Long) = entries.findById(id) as Transaction.Transfer

    private fun assertNumber(expected: String, actual: BigDecimal) =
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))

    @Test
    fun aSameCurrencyTransferCopiesTheAmountOneToOneAtRateOne() = runTest {
        val rig = SaveEntryRig(accounts = allAccounts)

        val saved = rig.saveEntry(
            EntryWorld.transfer(amount = "40", destAmount = "39", rateUsed = "3"),
            typedRates = emptyMap(),
        )

        val stored = rig.storedTransfer(saved.id)
        assertEquals(Money.of("40", "USD"), stored.sourceAmount)
        assertEquals(Money.of("40", "USD"), stored.destAmount)
        assertNumber("1", stored.rateUsed)
    }

    @Test
    fun aCrossCurrencyTransferIsConvertedThroughTheSavedRates() = runTest {
        // base USD; 1 EUR = 1.10 USD, so 11 USD is 10.00 EUR.
        val rig = SaveEntryRig(accounts = allAccounts, savedRates = mapOf("EUR" to "1.10"))

        val saved = rig.saveEntry(
            EntryWorld.transfer(destAccountId = 4, amount = "11", destAmount = "999", to = "EUR", rateUsed = "7"),
            typedRates = emptyMap(),
        )

        val stored = rig.storedTransfer(saved.id)
        assertEquals(Money.of("11", "USD"), stored.sourceAmount)
        assertEquals(Money.of("10.00", "EUR"), stored.destAmount)
    }

    @Test
    fun theStoredRateIsTheRatePairRatherThanTheOneTheFormSent() = runTest {
        // 1 SYP = 0.0001 USD, so 1 USD = 10000 SYP.
        val rig = SaveEntryRig(accounts = allAccounts, savedRates = mapOf("SYP" to "0.0001"))

        val saved = rig.saveEntry(
            EntryWorld.transfer(destAccountId = 2, amount = "100", destAmount = "1", to = "SYP", rateUsed = "5"),
            typedRates = emptyMap(),
        )

        val stored = rig.storedTransfer(saved.id)
        assertEquals(Money.of("1000000", "SYP"), stored.destAmount)
        assertNumber("10000", stored.rateUsed)
    }

    @Test
    fun aTransferBetweenTwoCurrenciesWithNoRateIsRefusedAndNothingIsSaved() = runTest {
        val rig = SaveEntryRig(accounts = allAccounts, savedRates = emptyMap())

        val failure = runCatching {
            rig.saveEntry(
                EntryWorld.transfer(destAccountId = 4, amount = "5", to = "EUR"),
                typedRates = emptyMap(),
            )
        }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.MissingRate)
        assertEquals("EUR", (failure as WalletException.MissingRate).code)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun anAmountThatRoundsToZeroInTheDestinationIsRefusedAndNothingIsSaved() = runTest {
        // 1 EUR = 1000 USD, so 0.01 USD is 0.00001 EUR.
        val rig = SaveEntryRig(accounts = allAccounts, savedRates = mapOf("EUR" to "1000"))

        val failure = runCatching {
            rig.saveEntry(
                EntryWorld.transfer(destAccountId = 4, amount = "0.01", destAmount = "1", to = "EUR"),
                typedRates = emptyMap(),
            )
        }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.AmountRoundsToZero)
        assertEquals("EUR", (failure as WalletException.AmountRoundsToZero).currencyCode)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun aTypedRatePricesThisTransferAndLeavesTheSavedRateAloneWhenSavingIsOff() = runTest {
        // base SYP; 1 USD = 4 SYP saved, 5 typed: 2 USD is 10 SYP.
        val rig = SaveEntryRig(
            accounts = allAccounts,
            base = "SYP",
            savedRates = mapOf("USD" to "4"),
            saveRates = false,
        )

        val saved = rig.saveEntry(
            EntryWorld.transfer(sourceAccountId = 1, destAccountId = 2, amount = "2", destAmount = "1", to = "SYP"),
            typedRates = mapOf("USD" to BigDecimal("5")),
        )

        val stored = rig.storedTransfer(saved.id)
        assertEquals(Money.of("10", "SYP"), stored.destAmount)
        assertNumber("5", stored.rateUsed)
        assertNumber("4", rig.rates.findByCode("USD")!!.rateToBase)
    }

    @Test
    fun aTypedRateStandsInForAMissingSavedOne() = runTest {
        val rig = SaveEntryRig(accounts = allAccounts, base = "SYP", savedRates = emptyMap())

        val saved = rig.saveEntry(
            EntryWorld.transfer(sourceAccountId = 1, destAccountId = 2, amount = "2", destAmount = "1", to = "SYP"),
            typedRates = mapOf("USD" to BigDecimal("4")),
        )

        assertEquals(Money.of("8", "SYP"), rig.storedTransfer(saved.id).destAmount)
    }

    @Test
    fun aTypedRateForTheBaseCurrencyIsIgnored() = runTest {
        val rig = SaveEntryRig(accounts = allAccounts, base = "SYP", savedRates = mapOf("USD" to "4"))

        val saved = rig.saveEntry(
            EntryWorld.transfer(sourceAccountId = 1, destAccountId = 2, amount = "2", destAmount = "1", to = "SYP"),
            typedRates = mapOf("SYP" to BigDecimal("9")),
        )

        assertEquals(Money.of("8", "SYP"), rig.storedTransfer(saved.id).destAmount)
        assertNull(rig.rates.findByCode("SYP"))
    }
}
