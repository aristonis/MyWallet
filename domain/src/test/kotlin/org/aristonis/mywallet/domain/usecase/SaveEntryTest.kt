package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SaveEntryTest {

    private fun assertRate(expected: String, actual: BigDecimal?) {
        assertNotNull("a rate was expected but none is stored", actual)
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))
    }

    @Test
    fun returnsTheStoredIdAndDate() = runTest {
        val rig = SaveEntryRig()

        val saved = rig.saveEntry(EntryWorld.expense(amount = "12.50"), typedRates = emptyMap())

        assertEquals(EntryWorld.day, saved.date)
        val stored = rig.entries.findById(saved.id) as Transaction.Expense
        assertEquals(saved.id, stored.id)
        assertEquals(EntryWorld.day, stored.date)
        assertEquals(0, BigDecimal("12.50").compareTo(stored.amount.amount))
    }

    @Test
    fun aNewEntryInTheBaseCurrencyIsStoredAtRateOne() = runTest {
        val rig = SaveEntryRig(base = "USD")

        val saved = rig.saveEntry(EntryWorld.expense(), typedRates = emptyMap())

        assertRate("1", (rig.entries.findById(saved.id) as Transaction.Expense).rateToBase)
    }

    @Test
    fun aNewEntryTakesTheSavedRateOfItsCurrency() = runTest {
        val rig = SaveEntryRig(savedRates = mapOf("SYP" to "0.0001"))

        val saved = rig.saveEntry(
            EntryWorld.expense(accountId = 2, amount = "5000", currency = "SYP"),
            typedRates = emptyMap(),
        )

        assertRate("0.0001", (rig.entries.findById(saved.id) as Transaction.Expense).rateToBase)
    }

    @Test
    fun aTypedRateWithSaveRatesOffStaysOnTheEntryAndTheSavedRateIsUnchanged() = runTest {
        val rig = SaveEntryRig(savedRates = mapOf("SYP" to "0.0001"), saveRates = false)

        val saved = rig.saveEntry(
            EntryWorld.expense(accountId = 2, amount = "5000", currency = "SYP"),
            typedRates = mapOf("SYP" to BigDecimal("0.0002")),
        )

        assertRate("0.0002", (rig.entries.findById(saved.id) as Transaction.Expense).rateToBase)
        assertRate("0.0001", rig.rates.findByCode("SYP")!!.rateToBase)
    }

    @Test
    fun withSaveRatesOnTheTypedRateIsSaved() = runTest {
        val rig = SaveEntryRig(savedRates = mapOf("SYP" to "0.0001"), saveRates = true)

        val saved = rig.saveEntry(
            EntryWorld.expense(accountId = 2, amount = "5000", currency = "SYP"),
            typedRates = mapOf("SYP" to BigDecimal("0.0002")),
        )

        assertRate("0.0002", (rig.entries.findById(saved.id) as Transaction.Expense).rateToBase)
        assertRate("0.0002", rig.rates.findByCode("SYP")!!.rateToBase)
    }

    @Test
    fun aNewEntryWithNoRateIsSavedWithANullRate() = runTest {
        val rig = SaveEntryRig(savedRates = emptyMap())

        val saved = rig.saveEntry(
            EntryWorld.expense(accountId = 2, amount = "5000", currency = "SYP"),
            typedRates = emptyMap(),
        )

        assertNull((rig.entries.findById(saved.id) as Transaction.Expense).rateToBase)
    }

    @Test
    fun editingKeepsTheIdAndRewritesTheEntry() = runTest {
        val rig = SaveEntryRig(initialEntries = listOf(EntryWorld.expense(id = 7, rate = "1", note = "old")))

        val saved = rig.saveEntry(EntryWorld.expense(id = 7, rate = "1", note = "new"), typedRates = emptyMap())

        assertEquals(7L, saved.id)
        val all = rig.entries.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("new", all.single().note)
    }

    @Test
    fun editingAnEntryThatNoLongerExistsIsRefused() = runTest {
        val rig = SaveEntryRig()

        val failure = runCatching { rig.saveEntry(EntryWorld.expense(id = 99), typedRates = emptyMap()) }
            .exceptionOrNull()

        assertTrue(failure is WalletException.TransactionNotFound)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun anAmountInTheWrongCurrencyForTheAccountIsRefusedAndNothingIsSaved() = runTest {
        val rig = SaveEntryRig()

        val failure = runCatching {
            rig.saveEntry(EntryWorld.expense(accountId = 1, amount = "5", currency = "SYP"), typedRates = emptyMap())
        }.exceptionOrNull()

        assertTrue(failure is WalletException.CurrencyMismatch)
        assertTrue(rig.entries.rows.value.isEmpty())
    }
}
