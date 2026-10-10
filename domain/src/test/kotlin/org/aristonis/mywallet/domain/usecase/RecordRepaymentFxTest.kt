package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.day
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.opening
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.syp
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.usd
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class RecordRepaymentFxTest {
    @Test fun payingTheRestKeeps1000050SypExactAndMoves7693UsdAt13000() = runTest {
        val rig = sypDebt()
        val saved = rig.record(7, 1, null, day, pairRate = BigDecimal("13000"))
        val paid = rig.saved(saved.id)
        assertEquals(syp("1000050"), paid.principal)
        assertEquals(usd("76.93"), paid.accountAmount)
        assertRate("13000", paid.pairRate)
        assertEquals(syp("0"), rig.remaining())
    }

    @Test fun aPaidInFullCrossCurrencyNoteEditPreservesBothAmountsAndRate() = runTest {
        val rig = sypDebt()
        val saved = rig.record(7, 1, null, day, pairRate = BigDecimal("13000"))
        val before = rig.saved(saved.id)
        rig.record(7, 1, before.principal, day, id = saved.id, note = "receipt",
            typedRates = mapOf("SYP" to BigDecimal("0.0002")))
        assertEquals(before.copy(note = "receipt"), rig.saved(saved.id))
        assertEquals(syp("0"), rig.remaining())
    }

    @Test fun collectingUsesAccountToDebtPairDirectionAsPayingDoes() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(debtId = 8, side = DebtSide.OWED_TO_ME, amount = "100")))
        val saved = rig.record(8, 2, usd("100"), day, pairRate = BigDecimal("0.0001"))
        val paid = rig.saved(saved.id)
        assertEquals(syp("1000000.00"), paid.accountAmount)
        assertEquals(usd("100"), paid.principal)
        assertRate("0.0001", paid.pairRate)
        assertEquals(usd("0"), rig.remaining(8))
        assertEquals(listOf(syp("1000000")), rig.store.ledger.observeAccountLegs().first().map { it.amount })
    }

    @Test fun explicitlySuppliedPrincipalsStayExactWithAnExplicitPairRate() = runTest {
        val rig = sypDebt()
        val saved = rig.record(7, 1, syp("1000050"), day,
            accountPrincipal = usd("76.93"), pairRate = BigDecimal("13000"))
        assertEquals(syp("1000050"), rig.saved(saved.id).principal)
        assertEquals(usd("76.93"), rig.saved(saved.id).accountAmount)
        assertRate("13000", rig.saved(saved.id).pairRate)
        assertEquals(syp("0"), rig.remaining())
    }

    @Test fun explicitPrincipalsWithoutARateDeriveAccountToDebtRatioWithoutChangingAmounts() = runTest {
        val rig = sypDebt()
        val saved = rig.record(7, 1, syp("1000050"), day, accountPrincipal = usd("76.93"))
        val paid = rig.saved(saved.id)
        assertEquals(syp("1000050"), paid.principal)
        assertEquals(usd("76.93"), paid.accountAmount)
        val expected = BigDecimal("1000050").divide(BigDecimal("76.93"),
            org.aristonis.mywallet.domain.service.CurrencyConverter.RATE_SCALE,
            org.aristonis.mywallet.domain.service.CurrencyConverter.ROUNDING)
        assertEquals(0, expected.compareTo(paid.pairRate))
    }

    @Test fun aNewPaymentWithoutAPairRateUsesTheSavedFxRate() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(amount = "100")), savedRates = mapOf("SYP" to "0.0001"))
        val saved = rig.record(7, 2, usd("100"), day)
        assertEquals(syp("1000000"), rig.saved(saved.id).accountAmount)
        assertRate("0.0001", rig.saved(saved.id).pairRate)
    }

    @Test fun aTypedFxRateWinsOverTheSavedFxRateWhenPricingANewPayment() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(amount = "100")), savedRates = mapOf("SYP" to "0.0001"))
        val saved = rig.record(7, 2, usd("100"), day, typedRates = mapOf("SYP" to BigDecimal("0.0002")))
        assertEquals(syp("500000"), rig.saved(saved.id).accountAmount)
        assertRate("0.0002", rig.saved(saved.id).pairRate)
    }

    @Test fun anAmountEditUsesTheSamePairStoredRateBeforeTodaysRates() = runTest {
        val rig = RepaymentTestRig(savedRates = mapOf("SYP" to "0.0002"))
        val saved = rig.record(7, 2, usd("100"), day, pairRate = BigDecimal("0.0001"))
        rig.record(7, 2, usd("200"), day, id = saved.id)
        assertEquals(syp("2000000"), rig.saved(saved.id).accountAmount)
        assertRate("0.0001", rig.saved(saved.id).pairRate)
        assertEquals(usd("4800"), rig.remaining())
    }

    @Test fun anExplicitPositivePairRateWinsOverSavedFxWhenAnAmountChanges() = runTest {
        val rig = RepaymentTestRig(savedRates = mapOf("SYP" to "0.0002"))
        val saved = rig.record(7, 2, usd("100"), day, pairRate = BigDecimal("0.0001"))
        rig.record(7, 2, usd("200"), day, id = saved.id, pairRate = BigDecimal("0.0004"))
        assertEquals(syp("500000"), rig.saved(saved.id).accountAmount)
        assertRate("0.0004", rig.saved(saved.id).pairRate)
    }

    @Test fun crossCurrencyInterestIsInAccountCurrencyAndStampedAtItsTypedFxRate() = runTest {
        val rig = RepaymentTestRig()
        val saved = rig.record(7, 2, usd("100"), day, pairRate = BigDecimal("0.0001"),
            interest = syp("50000"), interestCategoryId = 6, typedRates = mapOf("SYP" to BigDecimal("0.0002")))
        val paid = rig.saved(saved.id)
        assertEquals(syp("1050000"), paid.accountAmount)
        assertEquals(usd("4900"), rig.remaining())
        val interestLeg = rig.store.ledger.observeCategoryLegs(DateRange.ALL_TIME).first().single()
        assertEquals(syp("50000"), interestLeg.amount)
        assertRate("0.0002", interestLeg.rateToBase)
    }

    @Test fun crossCurrencyInterestOnlyStillStoresAPositivePairRate() = runTest {
        val rig = RepaymentTestRig()
        val saved = rig.record(7, 2, usd("0"), day, interest = syp("50000"), pairRate = BigDecimal("0.0001"))
        assertEquals(syp("50000"), rig.saved(saved.id).accountAmount)
        assertEquals(usd("0"), rig.saved(saved.id).principal)
        assertRate("0.0001", rig.saved(saved.id).pairRate)
        assertEquals(usd("5000"), rig.remaining())
    }

    @Test fun aMissingFxRateRefusesPaymentInsteadOfGuessing() = runTest {
        val rig = RepaymentTestRig()
        val failure = runCatching { rig.record(7, 2, usd("100"), day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.MissingRate)
        assertEquals("SYP", (failure as WalletException.MissingRate).code)
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun aPositivePrincipalThatRoundsToZeroInAccountCurrencyIsRefused() = runTest {
        val rig = sypDebt()
        val failure = runCatching { rig.record(7, 1, syp("0.01"), day, pairRate = BigDecimal("13000")) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.AmountRoundsToZero)
        assertEquals("USD", (failure as WalletException.AmountRoundsToZero).currencyCode)
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun anUnchangedExplicitAccountPrincipalOnANoteAndDateEditKeepsTheHistoricalPairRate() = runTest {
        val rig = sypDebt()
        val saved = rig.record(7, 1, null, day, pairRate = BigDecimal("13000"))
        val before = rig.saved(saved.id)
        val editedDate = day.plusDays(1)

        rig.record(7, 1, before.principal, editedDate, id = saved.id,
            accountPrincipal = before.accountAmount, note = "receipt")

        val edited = rig.saved(saved.id)
        assertEquals(before.principal, edited.principal)
        assertEquals(before.accountAmount, edited.accountAmount)
        assertRate("13000", edited.pairRate)
        assertEquals(editedDate, edited.date)
        assertEquals("receipt", edited.note)
        assertEquals(syp("0"), rig.remaining())
    }

    @Test fun aChangedExplicitAccountPrincipalOnAnEditDerivesANewRatioWhenNoRateIsSupplied() = runTest {
        val rig = sypDebt()
        val saved = rig.record(7, 1, null, day, pairRate = BigDecimal("13000"))

        rig.record(7, 1, syp("1000050"), day, id = saved.id, accountPrincipal = usd("80"))

        val edited = rig.saved(saved.id)
        assertEquals(syp("1000050"), edited.principal)
        assertEquals(usd("80"), edited.accountAmount)
        assertRate("12500.625", edited.pairRate)
        assertEquals(syp("0"), rig.remaining())
    }

    @Test fun anExplicitNewPositivePairRateWinsOnAnEditEvenWhenBothAmountsAreUnchanged() = runTest {
        val rig = sypDebt()
        val saved = rig.record(7, 1, null, day, pairRate = BigDecimal("13000"))
        val before = rig.saved(saved.id)

        rig.record(7, 1, before.principal, day, id = saved.id,
            accountPrincipal = before.accountAmount, pairRate = BigDecimal("14000"))

        val edited = rig.saved(saved.id)
        assertEquals(before.principal, edited.principal)
        assertEquals(before.accountAmount, edited.accountAmount)
        assertRate("14000", edited.pairRate)
        assertEquals(syp("0"), rig.remaining())
    }

    private fun sypDebt() = RepaymentTestRig(debts = listOf(DebtEntryWorld.ali.copy(currencyCode = "SYP")),
        initialEntries = listOf(opening(amount = "1000050", currency = "SYP")))

    private fun assertRate(expected: String, actual: BigDecimal?) {
        assertNotNull(actual)
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }
}
