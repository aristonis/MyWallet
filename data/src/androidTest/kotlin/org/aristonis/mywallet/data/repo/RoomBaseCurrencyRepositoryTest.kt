package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.port.LegRateChange
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * A base change rewrites the rate table, the base currency and the rates stored on category legs as
 * one all-or-nothing write. A half-applied change would leave legs expressed against a base that
 * never changed, shown as ordinary totals.
 *
 * Assumed names:
 * - `data class LegRateChange(currencyCode: String, from: BigDecimal?, to: BigDecimal)` in
 *   `org.aristonis.mywallet.domain.port`: every category leg in `currencyCode` whose stored rate equals
 *   `from` (compared as text after the change is computed; a null `from` matches a leg with no rate)
 *   gets `to`. Account and debt legs are never touched.
 * - `RoomBaseCurrencyRepository(database).rebase(newBaseCurrencyCode, rates, legRates)`.
 *
 * Faults are injected with SQLite triggers that abort an update, so the repository's own transaction
 * is what has to undo the writes that landed before the abort.
 */
@RunWith(AndroidJUnit4::class)
class RoomBaseCurrencyRepositoryTest {

    private lateinit var db: WalletDatabase
    private lateinit var repository: RoomBaseCurrencyRepository

    private val sql get() = db.openHelper.writableDatabase

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomBaseCurrencyRepository(db)

        db.currencyDao().insertAll(
            listOf(
                CurrencyEntity("USD", "$", 2),
                CurrencyEntity("EUR", "€", 2),
                CurrencyEntity("JPY", "¥", 0),
            ),
        )
        db.rateDao().insertAll(
            listOf(
                RateEntity(currencyCode = "EUR", rateToBase = "1.25"),
                RateEntity(currencyCode = "JPY", rateToBase = "0.0025"),
            ),
        )
        db.settingsDao().upsert(
            SettingsEntity(baseCurrencyCode = "USD", theme = "DARK", schemaVersion = 1),
        )
        sql.execSQL("INSERT INTO entries (id, kind, date) VALUES (1, 'EXPENSE', 20454)")
        sql.execSQL("INSERT INTO entries (id, kind, date) VALUES (2, 'TRANSFER', 20454)")
        categoryLeg(id = 1, entryId = 1, position = 0, amount = "100", currency = "EUR", rate = "1.25")
        categoryLeg(id = 2, entryId = 1, position = 1, amount = "7", currency = "EUR", rate = null)
        categoryLeg(id = 3, entryId = 1, position = 2, amount = "4000", currency = "JPY", rate = "0.0025")
        categoryLeg(id = 4, entryId = 1, position = 3, amount = "200", currency = "USD", rate = "1")
        categoryLeg(id = 5, entryId = 1, position = 4, amount = "500", currency = "JPY", rate = null)
        sql.execSQL(
            "INSERT INTO entry_legs (id, entryId, role, position, accountId, amount, currencyCode) " +
                "VALUES (6, 2, 'SOURCE', 0, 1, '-100', 'EUR')",
        )
    }

    @After
    fun tearDown() = db.close()

    private fun categoryLeg(id: Long, entryId: Long, position: Int, amount: String, currency: String, rate: String?) {
        sql.execSQL(
            "INSERT INTO entry_legs (id, entryId, role, position, categoryId, amount, currencyCode, rateToBase) " +
                "VALUES (?, ?, 'MAIN', ?, 3, ?, ?, ?)",
            arrayOf<Any?>(id, entryId, position, amount, currency, rate),
        )
    }

    private fun legRate(id: Long): String? =
        sql.query("SELECT rateToBase FROM entry_legs WHERE id = $id").use { c ->
            assertTrue(c.moveToFirst())
            if (c.isNull(0)) null else c.getString(0)
        }

    private fun storedRates(): Map<String, String> =
        sql.query("SELECT currencyCode, rateToBase FROM rates").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    private fun baseCode(): String =
        sql.query("SELECT baseCurrencyCode FROM settings WHERE id = 0").use { c ->
            assertTrue(c.moveToFirst())
            c.getString(0)
        }

    private fun assertLegRate(expected: String?, id: Long) {
        val actual = legRate(id)
        if (expected == null) {
            assertNull("leg $id should have no rate but has $actual", actual)
        } else {
            assertNotNull("leg $id should have $expected but has none", actual)
            assertEquals("leg $id", 0, BigDecimal(expected).compareTo(BigDecimal(actual!!)))
        }
    }

    /** USD to EUR at 1 EUR = 1.25 USD: the new rates and the matching leg changes. */
    private val newRates = listOf(
        ExchangeRate("USD", BigDecimal("0.8")),
        ExchangeRate("JPY", BigDecimal("0.002")),
    )
    private val legChanges = listOf(
        LegRateChange("EUR", BigDecimal("1.25"), BigDecimal.ONE),
        LegRateChange("EUR", null, BigDecimal.ONE),
        LegRateChange("JPY", BigDecimal("0.0025"), BigDecimal("0.002")),
        LegRateChange("USD", BigDecimal.ONE, BigDecimal("0.8")),
    )

    private fun assertNothingChanged() {
        assertEquals("USD", baseCode())
        assertEquals(setOf("EUR", "JPY"), storedRates().keys)
        assertEquals(0, BigDecimal("1.25").compareTo(BigDecimal(storedRates().getValue("EUR"))))
        assertLegRate("1.25", 1)
        assertLegRate(null, 2)
        assertLegRate("0.0025", 3)
        assertLegRate("1", 4)
        assertLegRate(null, 5)
    }

    @Test
    fun aRebaseSwitchesTheBaseTheRatesAndTheCategoryLegsTogether() = runBlocking {
        repository.rebase("EUR", newRates, legChanges)

        assertEquals("EUR", baseCode())
        assertEquals(setOf("USD", "JPY"), storedRates().keys)
        assertLegRate("1", 1)
        assertLegRate("1", 2)
        assertLegRate("0.002", 3)
        assertLegRate("0.8", 4)
        assertLegRate(null, 5)
    }

    @Test
    fun accountLegsAreNeverGivenARate() = runBlocking {
        repository.rebase("EUR", newRates, legChanges)

        assertNull(legRate(6))
    }

    @Test
    fun aLegIsRewrittenOnceEvenWhenAnotherChangeStartsFromItsNewValue() = runBlocking {
        // Applied one after another, 0.0025 -> 0.002 and then 0.002 -> 0.0016 would chain on leg 3.
        categoryLeg(id = 7, entryId = 1, position = 5, amount = "10", currency = "JPY", rate = "0.002")

        repository.rebase("EUR", newRates, legChanges + LegRateChange("JPY", BigDecimal("0.002"), BigDecimal("0.0016")))

        assertLegRate("0.002", 3)
        assertLegRate("0.0016", 7)
    }

    @Test
    fun aFaultAfterTheRatesAreWrittenRollsBackTheRatesTheLegsAndTheBase() = runBlocking {
        // The settings write is the last of the three, so the rates and legs are already stored.
        sql.execSQL("CREATE TRIGGER fail_settings BEFORE UPDATE ON settings BEGIN SELECT RAISE(ABORT, 'injected'); END")

        val failure = runCatching { repository.rebase("EUR", newRates, legChanges) }.exceptionOrNull()

        assertNotNull("the injected fault must surface", failure)
        assertNothingChanged()
    }

    @Test
    fun aFaultWhileTheLegsAreUpdatedRollsBackTheRatesAndTheBase() = runBlocking {
        sql.execSQL("CREATE TRIGGER fail_legs BEFORE UPDATE ON entry_legs BEGIN SELECT RAISE(ABORT, 'injected'); END")

        val failure = runCatching { repository.rebase("EUR", newRates, legChanges) }.exceptionOrNull()

        assertNotNull("the injected fault must surface", failure)
        assertNothingChanged()
    }

    @Test
    fun aFaultWhileTheRatesAreWrittenRollsBackTheLegsAndTheBase() = runBlocking {
        sql.execSQL("CREATE TRIGGER fail_rates BEFORE INSERT ON rates BEGIN SELECT RAISE(ABORT, 'injected'); END")

        val failure = runCatching { repository.rebase("EUR", newRates, legChanges) }.exceptionOrNull()

        assertNotNull("the injected fault must surface", failure)
        assertNothingChanged()
    }

    @Test
    fun theThemeAndSchemaVersionSurviveARebase() = runBlocking {
        repository.rebase("EUR", newRates, legChanges)

        val settings = db.settingsDao().get()!!
        assertEquals("DARK", settings.theme)
        assertEquals(1, settings.schemaVersion)
    }

    @Test
    fun aRebaseWithNoLegChangesStillSwitchesTheBaseAndTheRates() = runBlocking {
        repository.rebase("EUR", newRates, emptyList())

        assertEquals("EUR", baseCode())
        assertEquals(setOf("USD", "JPY"), storedRates().keys)
        assertLegRate("1.25", 1)
    }
}
