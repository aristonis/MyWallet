package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * The save decides the rate on every leg. Whatever rate the form carries on the entry it sends, a
 * stale one from a screen opened yesterday must never end up in the books.
 */
class SaveEntryRateDecisionTest {

    private suspend fun rateOf(rig: SaveEntryRig, id: Long): BigDecimal? =
        (rig.entries.findById(id) as Transaction.Expense).rateToBase

    private fun assertRate(expected: String, actual: BigDecimal?) {
        assertNotNull("a rate was expected but none is stored", actual)
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))
    }

    @Test
    fun anEditKeepsTheStoredRateWhenTheFormSendsNoRate() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(EntryWorld.expense(id = 4, rate = "13000")),
            base = "SYP",
            savedRates = mapOf("USD" to "14000"),
        )

        rig.saveEntry(EntryWorld.expense(id = 4, rate = null, note = "after"), typedRates = emptyMap())

        assertRate("13000", rateOf(rig, 4))
    }

    @Test
    fun anEditKeepsTheStoredRateWhenTheFormSendsTodaysRate() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(EntryWorld.expense(id = 4, rate = "13000")),
            base = "SYP",
            savedRates = mapOf("USD" to "14000"),
        )

        rig.saveEntry(EntryWorld.expense(id = 4, rate = "14000", note = "after"), typedRates = emptyMap())

        assertRate("13000", rateOf(rig, 4))
    }

    @Test
    fun anIncomeEditAlsoKeepsTheStoredRateWhateverTheFormSends() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(EntryWorld.income(id = 4, rate = "13000")),
            base = "SYP",
            savedRates = mapOf("USD" to "14000"),
        )

        rig.saveEntry(EntryWorld.income(id = 4, rate = "14000"), typedRates = emptyMap())

        assertRate("13000", (rig.entries.findById(4) as Transaction.Income).rateToBase)
    }

    @Test
    fun aNewEntryInTheBaseCurrencyIsStoredAtOneWhateverTheFormSends() = runTest {
        val rig = SaveEntryRig(base = "USD")

        val saved = rig.saveEntry(EntryWorld.expense(rate = "2"), typedRates = emptyMap())

        assertRate("1", rateOf(rig, saved.id))
    }

    @Test
    fun aNewEntryTakesTheSavedRateNotAStaleOneFromTheForm() = runTest {
        val rig = SaveEntryRig(savedRates = mapOf("SYP" to "0.0001"))

        val saved = rig.saveEntry(
            EntryWorld.expense(accountId = 2, amount = "5000", currency = "SYP", rate = "0.5"),
            typedRates = emptyMap(),
        )

        assertRate("0.0001", rateOf(rig, saved.id))
    }

    @Test
    fun aNewEntryWithNoSavedRateAndNothingTypedIsStoredWithoutARateEvenIfTheFormSendsOne() = runTest {
        val rig = SaveEntryRig(savedRates = emptyMap())

        val saved = rig.saveEntry(
            EntryWorld.expense(accountId = 2, amount = "5000", currency = "SYP", rate = "0.5"),
            typedRates = emptyMap(),
        )

        assertNull(rateOf(rig, saved.id))
    }
}
