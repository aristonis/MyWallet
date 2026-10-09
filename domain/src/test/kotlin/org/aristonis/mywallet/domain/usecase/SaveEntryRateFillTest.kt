package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.DirectTransactionRunner
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCategoryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.FakeRateRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.aristonis.mywallet.domain.usecase.fake.RecordingLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * With "Save rates I enter" on, saving a typed rate fills the older legs of that currency through the
 * rate use case, not through the save itself, so the fill happens once and a rate saved from the
 * rates screen behaves the same way. The save has no ledger of its own to fill through; the only
 * ledger in the wiring is the rate use case's, and these tests count what reaches it.
 */
class SaveEntryRateFillTest {

    private fun sypExpense(id: Long = 0, rate: String? = null) =
        EntryWorld.expense(id = id, accountId = 2, amount = "5000", currency = "SYP", rate = rate)

    private suspend fun rateOf(entries: FakeEntryRepository, id: Long): BigDecimal? =
        (entries.findById(id) as Transaction.Expense).rateToBase

    private fun assertRate(expected: String, actual: BigDecimal?) {
        assertNotNull("a rate was expected but none is stored", actual)
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))
    }

    /** A save whose rate use case writes through a recording ledger. */
    private class Wiring(saveRates: Boolean, older: List<Transaction>, savedRates: List<ExchangeRate> = emptyList()) {
        val entries = FakeEntryRepository(older)
        val rates = FakeRateRepository(savedRates)
        val rateLedger = RecordingLedgerQueries(FakeLedgerQueries(entries))
        private val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD", saveTransactionRates = saveRates))
        val saveEntry = SaveEntry(
            runner = DirectTransactionRunner(),
            accounts = FakeAccountRepository(listOf(EntryWorld.usdAccount, EntryWorld.sypAccount)),
            categories = FakeCategoryRepository(listOf(EntryWorld.incomeCategory, EntryWorld.expenseCategory)),
            entries = entries,
            fx = fakeFx(EntryWorld.currencies, savedRates, "USD"),
            settings = settings,
            setExchangeRate = SetExchangeRate(FakeCurrencyRepository(EntryWorld.currencies), rates, rateLedger, DirectTransactionRunner(), settings),
        )
    }

    @Test
    fun theTypedRateIsGivenToOlderLegsExactlyOnceThroughTheRateUseCase() = runTest {
        val wiring = Wiring(saveRates = true, older = listOf(sypExpense(id = 1)))

        wiring.saveEntry(sypExpense(), typedRates = mapOf("SYP" to BigDecimal("0.0002")))

        assertEquals("one fill for one typed rate", 1, wiring.rateLedger.fills.size)
        assertEquals("SYP", wiring.rateLedger.fills.single().first)
        assertEquals(0, BigDecimal("0.0002").compareTo(wiring.rateLedger.fills.single().second))
    }

    @Test
    fun theOlderLegIsStillFilledAndTheNewEntryKeepsTheTypedRate() = runTest {
        val wiring = Wiring(saveRates = true, older = listOf(sypExpense(id = 1)))

        val saved = wiring.saveEntry(sypExpense(), typedRates = mapOf("SYP" to BigDecimal("0.0002")))

        assertRate("0.0002", rateOf(wiring.entries, 1))
        assertRate("0.0002", rateOf(wiring.entries, saved.id))
        assertRate("0.0002", wiring.rates.findByCode("SYP")!!.rateToBase)
    }

    @Test
    fun withSavingOffNothingIsSavedAndNothingIsFilled() = runTest {
        val wiring = Wiring(saveRates = false, older = listOf(sypExpense(id = 1)))

        val saved = wiring.saveEntry(sypExpense(), typedRates = mapOf("SYP" to BigDecimal("0.0002")))

        assertEquals(emptyList<Pair<String, BigDecimal>>(), wiring.rateLedger.fills)
        assertNull(rateOf(wiring.entries, 1))
        assertNull(wiring.rates.findByCode("SYP"))
        assertRate("0.0002", rateOf(wiring.entries, saved.id))
    }

    @Test
    fun aTypedRateEqualToTheSavedOneStillFillsOlderLegsWithNoRate() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(sypExpense(id = 1, rate = null)),
            savedRates = mapOf("SYP" to "0.0002"),
            saveRates = true,
        )

        rig.saveEntry(sypExpense(), typedRates = mapOf("SYP" to BigDecimal("0.0002")))

        assertRate("0.0002", rateOf(rig.entries, 1))
    }

    @Test
    fun theBaseCurrencyIsNeverSavedOrFilled() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(EntryWorld.expense(id = 1, rate = null)),
            saveRates = true,
        )

        rig.saveEntry(EntryWorld.expense(), typedRates = mapOf("USD" to BigDecimal("1")))

        assertNull(rig.rates.findByCode("USD"))
    }
}
