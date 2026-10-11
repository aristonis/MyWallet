package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.HomeSummary
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeFxRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSummaryMissingRateWarningTest {
    @Test fun anUnrelatedMissingRateKeepsNegativeDebtIdsSortedAndAccessible() = runTest {
        val legs = listOf(DebtLeg(9, DebtSide.I_OWE, Money.of("-3", "USD")),
            DebtLeg(2, DebtSide.OWED_TO_ME, Money.of("-1", "USD")),
            DebtLeg(5, DebtSide.I_OWE, Money.of("10", "EUR")))
        val ledger = object : LedgerQueries by FakeLedgerQueries(FakeEntryRepository()) {
            override fun observeDebtLegs() = flowOf(legs)
        }
        val fx = FakeFxRepository(FxSnapshot("USD", emptyMap(), listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))))
        val summary = ComputeHomeSummary(ledger, fx)(flowOf(emptyList())).first() as HomeSummary.MissingRate
        assertEquals("EUR", summary.currencyCode)
        assertEquals(listOf(2L, 9L), summary.debtsBelowZero)
    }
}
