package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.InjectedFault
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** What a rate typed on the form does: it prices the entry, is saved, and fills older entries without one. */
class SaveEntryTypedRateTest {

    private val eurAccount = EntryWorld.account(4, "EUR")
    private val allAccounts =
        listOf(EntryWorld.usdAccount, EntryWorld.sypAccount, EntryWorld.secondUsdAccount, eurAccount)

    private suspend fun rateOf(rig: SaveEntryRig, id: Long): BigDecimal? =
        (rig.entries.findById(id) as Transaction.Expense).rateToBase

    private fun sypExpense(id: Long = 0, rate: String? = null) =
        EntryWorld.expense(id = id, accountId = 2, amount = "5000", currency = "SYP", rate = rate)

    private fun assertRate(expected: String, actual: BigDecimal?) {
        assertNotNull("a rate was expected but none is stored", actual)
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))
    }

    // --- atomicity after the rate is written ---

    @Test
    fun aFaultAfterTheRateIsWrittenRollsBackTheEntryTheRateAndTheFilledLegs() = runTest {
        val older = sypExpense(id = 1)
        val rig = SaveEntryRig(
            initialEntries = listOf(older),
            savedRates = mapOf("SYP" to "0.0001"),
            saveRates = true,
            faultAfterFill = true,
            atomic = true,
        )

        val failure = runCatching {
            rig.saveEntry(sypExpense(), typedRates = mapOf("SYP" to BigDecimal("0.0009")))
        }.exceptionOrNull()

        assertTrue("was $failure", failure is InjectedFault)
        assertEquals(listOf(1L), rig.entries.rows.value.map { it.header.id })
        assertNull(rateOf(rig, 1))
        assertRate("0.0001", rig.rates.findByCode("SYP")!!.rateToBase)
    }

    @Test
    fun aFaultAfterTheRateIsWrittenDuringAnEditLeavesTheStoredEntryAsItWas() = runTest {
        val original = sypExpense(id = 3, rate = "0.0001")
        val rig = SaveEntryRig(
            initialEntries = listOf(original),
            savedRates = mapOf("SYP" to "0.0001"),
            faultAfterFill = true,
            atomic = true,
        )

        runCatching { rig.saveEntry(sypExpense(id = 3, rate = "0.0001"), typedRates = mapOf("SYP" to BigDecimal("0.0009"))) }

        assertEquals(original, rig.entries.findById(3))
        assertRate("0.0001", rig.rates.findByCode("SYP")!!.rateToBase)
    }

    // --- filling older entries that have no rate ---

    @Test
    fun withSavingOnTheTypedRateFillsOlderEntriesOfThatCurrencyThatHaveNone() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(sypExpense(id = 1, rate = null)),
            savedRates = emptyMap(),
            saveRates = true,
        )

        rig.saveEntry(sypExpense(), typedRates = mapOf("SYP" to BigDecimal("0.0002")))

        assertRate("0.0002", rateOf(rig, 1))
    }

    @Test
    fun theFillLeavesOtherCurrenciesAndAlreadyRatedEntriesAlone() = runTest {
        val euroWithoutRate = EntryWorld.expense(id = 2, accountId = 4, currency = "EUR", rate = null)
        val rig = SaveEntryRig(
            initialEntries = listOf(sypExpense(id = 1, rate = "0.00005"), euroWithoutRate),
            accounts = allAccounts,
            savedRates = emptyMap(),
            saveRates = true,
        )

        rig.saveEntry(sypExpense(), typedRates = mapOf("SYP" to BigDecimal("0.0002")))

        assertRate("0.00005", rateOf(rig, 1))
        assertNull(rateOf(rig, 2))
    }

    @Test
    fun withSavingOffNothingIsFilledAndNoRateIsSaved() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(sypExpense(id = 1, rate = null)),
            savedRates = emptyMap(),
            saveRates = false,
        )

        val saved = rig.saveEntry(sypExpense(), typedRates = mapOf("SYP" to BigDecimal("0.0002")))

        assertNull(rateOf(rig, 1))
        assertNull(rig.rates.findByCode("SYP"))
        assertRate("0.0002", rateOf(rig, saved.id))
    }

    // --- typed rates that make no sense ---

    @Test
    fun aTypedRateOfZeroSavesNothing() = runTest {
        val rig = SaveEntryRig(savedRates = emptyMap())

        val failure = runCatching { rig.saveEntry(sypExpense(), typedRates = mapOf("SYP" to BigDecimal.ZERO)) }
            .exceptionOrNull()

        assertNotNull(failure)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertNull(rig.rates.findByCode("SYP"))
    }

    @Test
    fun aNegativeTypedRateSavesNothing() = runTest {
        val rig = SaveEntryRig(savedRates = emptyMap())

        val failure = runCatching { rig.saveEntry(sypExpense(), typedRates = mapOf("SYP" to BigDecimal("-1"))) }
            .exceptionOrNull()

        assertNotNull(failure)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertNull(rig.rates.findByCode("SYP"))
    }

    @Test
    fun aTypedRateForAnUnknownCurrencySavesNothing() = runTest {
        val rig = SaveEntryRig(savedRates = emptyMap())

        val failure = runCatching {
            rig.saveEntry(EntryWorld.expense(), typedRates = mapOf("XYZ" to BigDecimal("2")))
        }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.CurrencyNotFound)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertNull(rig.rates.findByCode("XYZ"))
    }

    @Test
    fun aTypedRateForTheBaseCurrencyIsNotSavedAndTheEntryStaysAtOne() = runTest {
        val rig = SaveEntryRig(base = "USD", saveRates = true)

        val saved = rig.saveEntry(EntryWorld.expense(), typedRates = mapOf("USD" to BigDecimal("5")))

        assertRate("1", rateOf(rig, saved.id))
        assertNull(rig.rates.findByCode("USD"))
    }

    // --- a refused entry keeps no typed rate ---

    @Test
    fun anEntryRefusedByAnAccountRuleKeepsNoTypedRate() = runTest {
        val rig = SaveEntryRig(
            accounts = listOf(EntryWorld.usdAccount, EntryWorld.sypAccount.copy(archived = true)),
            saveRates = true,
        )

        val failure = runCatching {
            rig.saveEntry(sypExpense(), typedRates = mapOf("SYP" to BigDecimal("0.0002")))
        }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.AccountArchived)
        assertNull(rig.rates.findByCode("SYP"))
    }

    @Test
    fun aTransferRefusedForRoundingToZeroKeepsNoTypedRate() = runTest {
        val rig = SaveEntryRig(accounts = allAccounts, saveRates = true)

        val failure = runCatching {
            rig.saveEntry(
                EntryWorld.transfer(destAccountId = 4, amount = "0.01", to = "EUR"),
                typedRates = mapOf("EUR" to BigDecimal("1000")),
            )
        }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.AmountRoundsToZero)
        assertNull(rig.rates.findByCode("EUR"))
    }
}
