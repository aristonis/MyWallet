package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** How the owner's fee choices survive the other choices on the add form. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferFeeChoiceTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()
    private val date = LocalDate.of(2026, 10, 12)

    private fun typedSixDollarFee(f: EntryFeeFixture) {
        f.add.selectFeeMode(FeeMode.FIXED)
        f.add.setFeeValue("6")
    }

    @Test fun `a typed fee survives switching to another same-currency source account`() = runTest(dispatcher) {
        val f = EntryFeeFixture(date); runCurrent(); f.transfer(); typedSixDollarFee(f)
        f.add.selectAccount(4)
        val fee = f.add.state.value.fee
        assertEquals(FeeMode.FIXED, fee.mode)
        assertEquals("6", fee.valueInput)
        assertEquals(Money.of("294", "USD"), fee.accountMovement)
        f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        assertEquals(Money.of("6", "USD"), (f.use.entries.stored.single() as Transaction.Transfer).fee)
    }

    @Test fun `a typed fee is not replaced by the new source account default`() = runTest(dispatcher) {
        val f = EntryFeeFixture(date); runCurrent(); f.add.selectAccount(4); f.transfer()
        assertEquals("", f.add.state.value.fee.valueInput)
        f.add.setFeeValue("6")
        f.add.selectAccount(1)
        assertEquals(FeeMode.FIXED, f.add.state.value.fee.mode)
        assertEquals("6", f.add.state.value.fee.valueInput)
        assertEquals(Money.of("6", "USD"), f.add.state.value.fee.amount)
    }

    @Test fun `an untouched fee takes the new source account default`() = runTest(dispatcher) {
        val f = EntryFeeFixture(date); runCurrent(); f.add.selectAccount(4); f.transfer()
        f.add.selectAccount(1)
        assertEquals(FeeMode.PERCENT, f.add.state.value.fee.mode)
        assertEquals("2", f.add.state.value.fee.valueInput)
        assertEquals(Money.of("6", "USD"), f.add.state.value.fee.amount)
    }

    @Test fun `a fixed fee cleared by a currency change refills from the next default`() = runTest(dispatcher) {
        val f = EntryFeeFixture(date); runCurrent(); f.transfer(); typedSixDollarFee(f)
        f.add.selectAccount(3)
        assertEquals("", f.add.state.value.fee.valueInput)
        f.add.selectAccount(1)
        assertEquals(FeeMode.PERCENT, f.add.state.value.fee.mode)
        assertEquals("2", f.add.state.value.fee.valueInput)
    }

    @Test fun `re-selecting the current type leaves the form unchanged`() = runTest(dispatcher) {
        val f = EntryFeeFixture(date); runCurrent()
        f.add.selectCategory(4); f.add.setAmount("50"); f.add.setFeeValue("1")
        val before = f.add.state.value
        f.add.selectType(TransactionType.EXPENSE)
        assertSame(before, f.add.state.value)
        assertEquals(4L, f.add.state.value.selectedCategoryId)
        assertEquals("1", f.add.state.value.fee.valueInput)
    }

    @Test fun `switching type clears the removed fee category notice`() = runTest(dispatcher) {
        val f = EntryFeeFixture(date); runCurrent(); f.transfer(); f.add.selectFeeCategory(4)
        f.categoryFlow.value = f.categoryList.filterNot { it.id == 4L }; runCurrent()
        assertTrue(f.add.state.value.fee.categoryRemoved)
        f.add.selectType(TransactionType.EXPENSE)
        assertFalse(f.add.state.value.fee.categoryRemoved)
    }

    @Test fun `a same-currency transfer outside the base shows and stamps the fee rate`() = runTest(dispatcher) {
        val f = EntryFeeFixture(date); f.rates.upsert(ExchangeRate("EUR", BigDecimal("1.1"))); runCurrent()
        f.add.selectAccount(3); f.add.selectType(TransactionType.TRANSFER); f.add.selectDestAccount(5)
        f.add.setAmount("300")
        assertTrue(f.add.state.value.rateFields.isEmpty())
        f.add.setFeeValue("6")
        assertEquals(listOf("EUR"), f.add.state.value.rateFields.map { it.currencyCode })
        assertEquals("1.1", f.add.state.value.rateFields.single().input)
        assertTrue(f.add.state.value.canSubmit)
        f.add.setRate("EUR", "1.2"); f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        val saved = f.use.entries.stored.single() as Transaction.Transfer
        assertEquals(Money.of("6", "EUR"), saved.fee)
        assertEquals(Money.of("294", "EUR"), saved.destAmount)
        assertEquals(0, BigDecimal("1.2").compareTo(saved.feeRateToBase))
    }

    @Test fun `a fixed fee with more decimals than its currency is refused`() = runTest(dispatcher) {
        val f = EntryFeeFixture(date); runCurrent(); f.transfer(); f.add.selectFeeMode(FeeMode.FIXED)
        f.add.setFeeValue("5.555")
        assertEquals(UiMessage.FeeTooManyDecimals("USD", 2), f.add.state.value.fee.error)
        assertFalse(f.add.state.value.canSubmit)
        f.add.submit(); runCurrent()
        assertTrue(f.use.entries.stored.isEmpty())
        f.add.setFeeValue("5.55")
        assertNull(f.add.state.value.fee.error)
        assertEquals(Money.of("294.45", "USD"), f.add.state.value.fee.accountMovement)
    }
}
