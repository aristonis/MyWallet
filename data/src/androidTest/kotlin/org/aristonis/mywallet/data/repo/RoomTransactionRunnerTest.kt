package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/** A rate kept for a transaction that is then refused must not survive the refusal. */
@RunWith(AndroidJUnit4::class)
class RoomTransactionRunnerTest {

    private lateinit var db: WalletDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aFailureInsideRollsBackEveryWrite() = runTest {
        db.currencyDao().insertAll(listOf(CurrencyEntity(code = "USD", symbol = "$", decimalPlaces = 2)))
        val rates = RoomRateRepository(db.rateDao())

        val failure = runCatching {
            RoomTransactionRunner(db).inTransaction {
                rates.upsert(ExchangeRate("USD", BigDecimal("5")))
                error("refused")
            }
        }.exceptionOrNull()

        assertEquals("refused", failure?.message)
        assertNull(rates.findByCode("USD"))
    }
}
