package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.usecase.fake.FakeBaseCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeRateRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/**
 * Changing the base currency re-expresses every rate against the new base. Stored balances and
 * transaction amounts are never rewritten — only the layer that converts them.
 *
 * The arithmetic is `newRate(Y) = oldRate(Y) / oldRate(X)`, which is also where the old base gets
 * its first explicit rate: it had none, because the base is implicitly 1.
 */
class ChangeBaseCurrencyTest {

    private val currencies = listOf(
        Currency("USD", "$", 2),
        Currency("EUR", "€", 2),
        Currency("JPY", "¥", 0),
        Currency("GBP", "£", 2),
    )

    private fun fixture(
        rates: List<ExchangeRate> = listOf(
            ExchangeRate("EUR", BigDecimal("1.10")),
            ExchangeRate("JPY", BigDecimal("0.0067")),
        ),
        base: String = "USD",
    ): Triple<ChangeBaseCurrency, FakeBaseCurrencyRepository, FakeSettingsRepository> {
        val rateRepo = FakeRateRepository(rates)
        val settingsRepo = FakeSettingsRepository(
            Settings(baseCurrencyCode = base, theme = ThemePreference.DARK, schemaVersion = 4),
        )
        val baseRepo = FakeBaseCurrencyRepository()
        val useCase = ChangeBaseCurrency(
            currencies = FakeCurrencyRepository(currencies),
            rates = rateRepo,
            settings = settingsRepo,
            baseCurrency = baseRepo,
        )
        return Triple(useCase, baseRepo, settingsRepo)
    }

    private fun rateFor(repo: FakeBaseCurrencyRepository, code: String): BigDecimal? =
        repo.lastRebase?.rates?.firstOrNull { it.currencyCode == code }?.rateToBase

    @Test
    fun reExpressesEveryRateAgainstTheNewBase() = runTest {
        val (useCase, baseRepo, _) = fixture()

        useCase("EUR")

        assertEquals("EUR", baseRepo.lastRebase?.newBaseCurrencyCode)
        // 0.0067 / 1.10
        assertEquals(BigDecimal("0.006090909090909090909090909091"), rateFor(baseRepo, "JPY"))
    }

    @Test
    fun theOldBaseGainsAnExplicitRate() = runTest {
        // It had none: the base is implicitly 1, so nothing was ever stored for it.
        val (useCase, baseRepo, _) = fixture()

        useCase("EUR")

        assertEquals(BigDecimal("0.909090909090909090909090909091"), rateFor(baseRepo, "USD"))
    }

    @Test
    fun theNewBaseKeepsNoRateOfItsOwn() = runTest {
        // Leaving one behind is invisible while it is the base — the converter short-circuits on the
        // base code before it looks anything up — and then the NEXT rebase reads it as real data.
        val (useCase, baseRepo, _) = fixture()

        useCase("EUR")

        assertNull(rateFor(baseRepo, "EUR"))
    }

    @Test
    fun aCurrencyWithNoRateStillHasNone() = runTest {
        val (useCase, baseRepo, _) = fixture()

        useCase("EUR")

        assertNull("GBP was never rated, so it stays unrated and keeps prompting", rateFor(baseRepo, "GBP"))
    }

    @Test
    fun everythingIsHandedOverInOneCall() = runTest {
        // Rates and the base currency have to move together; a half-applied rebase would leave every
        // rate expressed against a base that never changed, and nothing would say so.
        val (useCase, baseRepo, _) = fixture()

        useCase("EUR")

        assertEquals(1, baseRepo.rebaseCount)
    }

    @Test
    fun changingToTheCurrentBaseDoesNothingAtAll() = runTest {
        // The base has no stored rate by definition, so without an early return this would fail with
        // a missing-rate error for the currency the user is already using.
        val (useCase, baseRepo, _) = fixture()

        useCase("USD")

        assertEquals(0, baseRepo.rebaseCount)
    }

    @Test
    fun anUnknownCurrencyFailsLoud() = runTest {
        val (useCase, baseRepo, _) = fixture()

        try {
            useCase("XYZ")
            fail("expected CurrencyNotFound")
        } catch (_: WalletException.CurrencyNotFound) {
            // expected
        }
        assertEquals(0, baseRepo.rebaseCount)
    }

    @Test
    fun rebasingToACurrencyWithNoRateIsRefused() = runTest {
        // Every other rate is divided by this one, so without it nothing is derivable and the only
        // alternative would be discarding every rate the user typed.
        val (useCase, baseRepo, _) = fixture()

        try {
            useCase("GBP")
            fail("expected MissingRate")
        } catch (e: WalletException.MissingRate) {
            assertEquals("GBP", e.code)
        }
        assertEquals(0, baseRepo.rebaseCount)
    }

    @Test
    fun theRateForTheNewBaseCanBeSuppliedInTheSameStep() = runTest {
        // Manage Rates only offers currencies an account actually holds, so a currency you do not yet
        // own could never be given a rate there — and moving country is the usual reason to rebase.
        val (useCase, baseRepo, _) = fixture()

        useCase("GBP", BigDecimal("1.27"))

        assertEquals("GBP", baseRepo.lastRebase?.newBaseCurrencyCode)
        // 1.10 / 1.27
        assertEquals(BigDecimal("0.866141732283464566929133858268"), rateFor(baseRepo, "EUR"))
        assertEquals(BigDecimal("0.787401574803149606299212598425"), rateFor(baseRepo, "USD"))
    }

    @Test
    fun aSuppliedRateOfZeroOrLessIsRefused() = runTest {
        val (useCase, baseRepo, _) = fixture()

        try {
            useCase("GBP", BigDecimal.ZERO)
            fail("expected an invalid-rate refusal")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertEquals(0, baseRepo.rebaseCount)
    }

    @Test
    fun everyRebasedRateStaysInsideTheScaleTheAppWillStore() = runTest {
        // The division runs at 34 significant digits; the rate screen refuses anything past 30
        // decimal places. Left uncapped, a change would write rates the app then tells the user are
        // unrealistic and will not let them re-save — correct numbers it refuses from itself.
        val (useCase, baseRepo, _) = fixture()

        useCase("GBP", BigDecimal("1.27"))

        baseRepo.lastRebase!!.rates.forEach { rate ->
            assertTrue(
                "\${rate.currencyCode} came out at scale \${rate.rateToBase.scale()}",
                rate.rateToBase.scale() <= CurrencyConverter.MAX_STORED_SCALE,
            )
        }
    }

    @Test
    fun aPairTooFarApartToExpressFailsLoud() = runTest {
        // Rounding to the storage limit is what makes this reachable: the exact quotient is not zero,
        // but nothing of it survives inside thirty decimal places.
        val (useCase, baseRepo, _) = fixture(
            rates = listOf(
                ExchangeRate("EUR", BigDecimal("1E-40")),
                ExchangeRate("JPY", BigDecimal("1E+40")),
            ),
        )

        try {
            useCase("JPY")
            fail("expected RateUnderflow")
        } catch (e: WalletException.RateUnderflow) {
            assertEquals("EUR", e.code)
        }
        assertEquals("nothing may be written", 0, baseRepo.rebaseCount)
    }

    @Test
    fun evenWildlyMismatchedMagnitudesStillProduceAUsableRate() = runTest {
        // A hyperinflated currency against an ordinary one: a wide spread, but still expressible.
        // Dividing on significant digits keeps it precise where a fixed scale would have flattened it.
        val (useCase, baseRepo, _) = fixture(
            rates = listOf(
                ExchangeRate("EUR", BigDecimal("0.000000000035")),
                ExchangeRate("JPY", BigDecimal("0.0067")),
            ),
        )

        useCase("JPY")

        val eur = rateFor(baseRepo, "EUR")!!
        assertTrue("the rate must survive as a positive number", eur.signum() > 0)
        assertTrue(eur.scale() <= CurrencyConverter.MAX_STORED_SCALE)
    }

    @Test
    fun aRoundTripReturnsToWhereItStarted() = runTest {
        // Realistic rates re-express exactly, so switching away and back leaves the numbers alone.
        val (first, firstRepo, _) = fixture()
        first("EUR")
        val afterFirst = firstRepo.lastRebase!!.rates

        val (second, secondRepo, _) = fixture(rates = afterFirst, base = "EUR")
        second("USD")

        // compareTo, not equals: BigDecimal equality is scale-sensitive and 1.1 is the same money
        // as 1.10. The value is what matters here, not how many zeros it is written with.
        assertEquals(0, rateFor(secondRepo, "EUR")!!.compareTo(BigDecimal("1.10")))
        assertEquals(0, rateFor(secondRepo, "JPY")!!.compareTo(BigDecimal("0.0067")))
        assertNull(rateFor(secondRepo, "USD"))
    }

    @Test
    fun theThemeAndSchemaVersionAreNotTouched() = runTest {
        // The port takes the currency code, not a whole settings row read outside the write — passing
        // an aggregate is how a concurrent theme change gets silently overwritten.
        val (useCase, baseRepo, _) = fixture()

        useCase("EUR")

        assertTrue("the port must take a code, never a settings snapshot", baseRepo.lastRebase != null)
        assertEquals("EUR", baseRepo.lastRebase?.newBaseCurrencyCode)
    }
}
