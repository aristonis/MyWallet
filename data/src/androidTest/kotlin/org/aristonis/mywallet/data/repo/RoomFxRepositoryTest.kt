package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * The base currency and the rates expressed against it are one state stored in two tables. Room
 * invalidates per table, so reading them as two streams lets a base change be seen with the old
 * rates still attached — a converted total that is simply wrong, shown as an ordinary amount.
 *
 * These tests drive the real database, because the whole guarantee is about transaction behaviour
 * that no fake can reproduce.
 */
@RunWith(AndroidJUnit4::class)
class RoomFxRepositoryTest {

    private lateinit var db: WalletDatabase
    private lateinit var repository: RoomFxRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomFxRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedUsdBase() {
        db.currencyDao().insertAll(
            listOf(
                CurrencyEntity(code = "USD", symbol = "$", decimalPlaces = 2),
                CurrencyEntity(code = "EUR", symbol = "€", decimalPlaces = 2),
                CurrencyEntity(code = "JPY", symbol = "¥", decimalPlaces = 0),
            ),
        )
        db.rateDao().insertAll(
            listOf(
                RateEntity(currencyCode = "EUR", rateToBase = "1.10"),
                RateEntity(currencyCode = "JPY", rateToBase = "0.0067"),
            ),
        )
        db.settingsDao().upsert(SettingsEntity(baseCurrencyCode = "USD", theme = "SYSTEM", schemaVersion = 1))
    }

    @Test
    fun readsTheBaseCurrencyRatesAndDecimalsTogether() = runTest {
        seedUsdBase()

        val snapshot = repository.observeFx().first()

        assertEquals("USD", snapshot.baseCurrencyCode)
        assertEquals(BigDecimal("1.10"), snapshot.ratesToBase["EUR"])
        assertEquals(0, snapshot.decimalPlaces["JPY"])
        assertTrue("the base needs no stored rate", snapshot.hasRateFor("USD"))
        assertTrue(snapshot.hasRateFor("EUR"))
    }

    @Test
    fun aRebaseIsNeverObservedWithTheOldRates() = runTest {
        // Swap the base and every rate in one transaction, the way a re-base commits, then read.
        // Reading settings and rates as separate streams could observe EUR-as-base while EUR still
        // carried its USD-era rate of 1.10 — the snapshot must never contain that pairing.
        seedUsdBase()
        assertEquals("USD", repository.observeFx().first().baseCurrencyCode)

        db.withTransaction {
            db.rateDao().clear()
            db.rateDao().insertAll(
                listOf(
                    RateEntity(currencyCode = "USD", rateToBase = "0.909090909091"),
                    RateEntity(currencyCode = "JPY", rateToBase = "0.006090909091"),
                ),
            )
            db.settingsDao().upsert(SettingsEntity(baseCurrencyCode = "EUR", theme = "SYSTEM", schemaVersion = 1))
        }

        val after = repository.observeFx().first()
        assertEquals("EUR", after.baseCurrencyCode)
        assertEquals("the new base carries no stored rate of its own", null, after.ratesToBase["EUR"])
        assertEquals(BigDecimal("0.909090909091"), after.ratesToBase["USD"])
        assertEquals(BigDecimal("0.006090909091"), after.ratesToBase["JPY"])
    }

    @Test
    fun aWalletWithNoSettingsYetReadsAsAnEmptyBase() = runTest {
        // Before onboarding writes settings there is nothing to convert; consumers show empty states
        // rather than the read failing.
        val snapshot = repository.observeFx().first()

        assertEquals("", snapshot.baseCurrencyCode)
        assertTrue(snapshot.ratesToBase.isEmpty())
    }
}

/**
 * The base currency and every rate move together or not at all. A half-applied change would leave
 * the rates re-expressed against a base that never changed — every converted figure wrong, and
 * nothing anywhere saying so.
 */
@RunWith(AndroidJUnit4::class)
class RoomBaseCurrencyRepositoryTest {

    private lateinit var db: WalletDatabase
    private lateinit var repository: RoomBaseCurrencyRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomBaseCurrencyRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        db.currencyDao().insertAll(
            listOf(
                CurrencyEntity(code = "USD", symbol = "$", decimalPlaces = 2),
                CurrencyEntity(code = "EUR", symbol = "€", decimalPlaces = 2),
            ),
        )
        db.rateDao().insertAll(listOf(RateEntity(currencyCode = "EUR", rateToBase = "1.10")))
        db.settingsDao().upsert(
            SettingsEntity(baseCurrencyCode = "USD", theme = "DARK", schemaVersion = 1),
        )
    }

    @Test
    fun replacesEveryRateAndSwapsTheBaseTogether() = runTest {
        seed()

        repository.rebase(
            "EUR",
            listOf(org.aristonis.mywallet.domain.model.ExchangeRate("USD", BigDecimal("0.909090909091"))),
        )

        assertEquals("EUR", db.settingsDao().get()!!.baseCurrencyCode)
        val stored = db.rateDao().getAll()
        assertEquals("replace, not merge — the new base keeps no row", 1, stored.size)
        assertEquals("USD", stored.single().currencyCode)
    }

    @Test
    fun keepsEveryOtherSettingUntouched() = runTest {
        // The row is re-read and copied inside the write rather than handed in from outside, so a
        // theme chosen a moment earlier is not carried away with a stale snapshot.
        seed()

        repository.rebase("EUR", emptyList())

        val settings = db.settingsDao().get()!!
        assertEquals("DARK", settings.theme)
        assertEquals(1, settings.schemaVersion)
    }

    @Test
    fun aFailureMidWayLeavesTheWalletExactlyAsItWas() = runTest {
        seed()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_rate BEFORE INSERT ON rates BEGIN SELECT RAISE(ABORT, 'no'); END;",
        )

        try {
            repository.rebase(
                "EUR",
                listOf(org.aristonis.mywallet.domain.model.ExchangeRate("USD", BigDecimal("0.9"))),
            )
            throw AssertionError("expected the insert to be refused")
        } catch (expected: Exception) {
            // the trigger aborts the transaction
        }

        assertEquals("the base must not have moved", "USD", db.settingsDao().get()!!.baseCurrencyCode)
        val rates = db.rateDao().getAll()
        assertEquals("the original rate must still be there", 1, rates.size)
        assertEquals("EUR", rates.single().currencyCode)
    }
}
