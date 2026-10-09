package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Editing an entry onto an account of another currency must not carry the old currency's rate along:
 * a USD rate of 13000 on a SYP amount would inflate Tracking with no error anywhere. The rule lives
 * in the save, so no screen can skip it. The edited entry still carries the old rate, as a form that
 * only changed the account would send it.
 */
class SaveEntryRateRestampTest {

    private suspend fun rateOf(rig: SaveEntryRig, id: Long): BigDecimal? =
        (rig.entries.findById(id) as Transaction.Expense).rateToBase

    private fun assertRate(expected: String, actual: BigDecimal?) {
        assertNotNull("a rate was expected but none is stored", actual)
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))
    }

    @Test
    fun aUsdExpenseAt13000EditedToASypAccountGetsTheSypRateWhenSypIsNotTheBase() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(EntryWorld.expense(id = 4, rate = "13000")),
            base = "EUR",
            savedRates = mapOf("USD" to "1.1", "SYP" to "0.0001"),
        )

        rig.saveEntry(
            EntryWorld.expense(id = 4, accountId = 2, amount = "130000", currency = "SYP", rate = "13000"),
            typedRates = emptyMap(),
        )

        assertRate("0.0001", rateOf(rig, 4))
    }

    @Test
    fun aUsdExpenseAt13000EditedToASypAccountGetsRateOneWhenSypIsTheBase() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(EntryWorld.expense(id = 4, rate = "13000")),
            base = "SYP",
            savedRates = mapOf("USD" to "13000"),
        )

        rig.saveEntry(
            EntryWorld.expense(id = 4, accountId = 2, amount = "130000", currency = "SYP", rate = "13000"),
            typedRates = emptyMap(),
        )

        assertRate("1", rateOf(rig, 4))
    }

    @Test
    fun anEditToAnotherCurrencyWithNoSavedRateStoresNullInsteadOfTheOldRate() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(EntryWorld.expense(id = 4, rate = "13000")),
            base = "EUR",
            savedRates = mapOf("USD" to "1.1"),
        )

        rig.saveEntry(
            EntryWorld.expense(id = 4, accountId = 2, amount = "130000", currency = "SYP", rate = "13000"),
            typedRates = emptyMap(),
        )

        assertEquals(null, rateOf(rig, 4))
    }

    @Test
    fun anUnchangedCurrencyKeepsTheRateTheEntryWasRecordedWith() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(EntryWorld.expense(id = 4, rate = "13000", note = "before")),
            base = "SYP",
            savedRates = mapOf("USD" to "14000"),
        )

        rig.saveEntry(
            EntryWorld.expense(id = 4, rate = "13000", note = "after"),
            typedRates = emptyMap(),
        )

        assertRate("13000", rateOf(rig, 4))
    }

    @Test
    fun aTypedRateWinsOverTheOldRateForAnUnchangedCurrency() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(EntryWorld.expense(id = 4, rate = "13000")),
            base = "SYP",
            savedRates = mapOf("USD" to "14000"),
        )

        rig.saveEntry(
            EntryWorld.expense(id = 4, rate = "13000"),
            typedRates = mapOf("USD" to BigDecimal("15000")),
        )

        assertRate("15000", rateOf(rig, 4))
    }

    @Test
    fun aTypedRateWinsForTheNewCurrencyAfterAnAccountChange() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(EntryWorld.expense(id = 4, rate = "13000")),
            base = "EUR",
            savedRates = mapOf("USD" to "1.1", "SYP" to "0.0001"),
            saveRates = false,
        )

        rig.saveEntry(
            EntryWorld.expense(id = 4, accountId = 2, amount = "130000", currency = "SYP", rate = "13000"),
            typedRates = mapOf("SYP" to BigDecimal("0.0003")),
        )

        assertRate("0.0003", rateOf(rig, 4))
    }

    @Test
    fun anIncomeEditedToAnotherCurrencyIsRestampedToo() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(EntryWorld.income(id = 4, rate = "1")),
            base = "USD",
            savedRates = mapOf("SYP" to "0.0001"),
        )

        rig.saveEntry(
            EntryWorld.income(id = 4, accountId = 2, amount = "100000", currency = "SYP", rate = "1"),
            typedRates = emptyMap(),
        )

        assertRate("0.0001", (rig.entries.findById(4) as Transaction.Income).rateToBase)
    }
}
