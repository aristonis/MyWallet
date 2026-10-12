package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class EntryFeeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()
    private val date = LocalDate.of(2026, 10, 12)
    private fun fixture(initial: List<Transaction> = emptyList(), missingRole: Boolean = false) =
        EntryFeeFixture(date, initial, missingRole)

    @Test fun `transfer fills source default and preview equals saved destination`() = runTest(dispatcher) {
        val f = fixture(); runCurrent(); f.transfer()
        assertEquals(FeeMode.PERCENT, f.add.state.value.fee.mode)
        assertEquals("2", f.add.state.value.fee.valueInput)
        assertEquals(Money.of("294", "USD"), f.add.state.value.fee.accountMovement)
        f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        val saved = f.use.entries.stored.single() as Transaction.Transfer
        assertEquals(Money.of("6", "USD"), saved.fee)
        assertEquals(Money.of("294", "USD"), saved.destAmount)
    }

    @Test fun `setting filled transfer fee to zero records no fee leg`() = runTest(dispatcher) {
        val f = fixture(); runCurrent(); f.transfer(); f.add.setFeeValue("0"); f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        val saved = f.use.entries.stored.single() as Transaction.Transfer
        assertNull(saved.fee)
        assertFalse(saved.toParts().legs.any { it.role == LegRole.FEE })
        assertEquals(Money.of("300", "USD"), saved.destAmount)
    }

    @Test fun `payment ignores account default and typed fee adds on top`() = runTest(dispatcher) {
        val f = fixture(); runCurrent()
        assertEquals("", f.add.state.value.fee.valueInput)
        f.add.selectCategory(4); f.add.setAmount("50"); f.add.setFeeValue("1")
        assertEquals(Money.of("51", "USD"), f.add.state.value.fee.accountMovement)
        f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        val saved = f.use.entries.stored.single() as Transaction.Expense
        assertEquals(Money.of("50", "USD"), saved.amount)
        assertEquals(Money.of("1", "USD"), saved.fee)
        assertEquals(Money.of("-51", "USD"), saved.toParts().legs.single { it.role == LegRole.ACCOUNT }.amount)
    }

    @Test fun `missing Fees offers picker and leaving it empty uses Uncategorized`() = runTest(dispatcher) {
        val f = fixture(missingRole = true); runCurrent(); f.transfer()
        assertTrue(f.add.state.value.fee.needsCategoryPicker)
        assertNull(f.add.state.value.fee.categoryId)
        f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        assertEquals(99L, (f.use.entries.stored.single() as Transaction.Transfer).feeCategoryId)
    }

    @Test fun `missing Fees accepts expense choice for just this entry`() = runTest(dispatcher) {
        val f = fixture(missingRole = true); runCurrent(); f.transfer(); f.add.selectFeeCategory(4)
        f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        assertEquals(4L, (f.use.entries.stored.single() as Transaction.Transfer).feeCategoryId)
        assertNull(f.roles.feeCategory)
    }

    @Test fun `editing transfer preserves recorded fixed fee without account autofill`() = runTest(dispatcher) {
        val original = Transaction.Transfer(7, 1, 2, Money.of("300", "USD"), Money.of("293", "USD"),
            BigDecimal.ONE, date, fee = Money.of("7", "USD"), feeCategoryId = 4)
        val f = fixture(listOf(original)); f.edit.load(7); runCurrent()
        assertEquals(FeeMode.FIXED, f.edit.state.value.fee.mode)
        assertEquals("7", f.edit.state.value.fee.valueInput)
        f.edit.setAmount("400"); f.edit.submit(); runCurrent()
        assertNull(f.edit.state.value.error)
        val saved = f.use.entries.stored.single() as Transaction.Transfer
        assertEquals(Money.of("7", "USD"), saved.fee)
        assertEquals(4L, saved.feeCategoryId)
        assertEquals(Money.of("393", "USD"), saved.destAmount)
    }

    @Test fun `refund offers only expense categories and saves as a refund`() = runTest(dispatcher) {
        val f = fixture(); runCurrent(); f.add.selectType(TransactionType.REFUND)
        assertEquals(listOf(4L, 5L, 99L), f.add.state.value.categoriesForType.map { it.id })
        assertTrue(TransactionType.REFUND in moreTransactionTypes)
        f.add.selectCategory(4); f.add.setAmount("200"); f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        val saved = f.use.entries.stored.single() as Transaction.Refund
        assertEquals(Money.of("200", "USD"), saved.amount)
        assertEquals(Money.of("-200", "USD"), saved.toParts().legs.single { it.role == LegRole.MAIN }.amount)
    }

    @Test fun `refund edit keeps its category and does not become income`() = runTest(dispatcher) {
        val original = Transaction.Refund(8, 2, Money.of("200", "USD"), 4, date = date)
        val f = fixture(listOf(original)); f.edit.load(8); runCurrent()
        assertEquals(TransactionType.REFUND, f.edit.state.value.type)
        assertEquals(listOf(4L, 5L, 99L), f.edit.state.value.categoriesForType.map { it.id })
        f.edit.setAmount("150"); f.edit.submit(); runCurrent()
        assertNull(f.edit.state.value.error)
        assertEquals(Money.of("150", "USD"), (f.use.entries.stored.single() as Transaction.Refund).amount)
    }
    @Test fun `role read failure rejects an active fee and retry recovers`() = runTest(dispatcher) {
        val f = fixture(); f.roles.failure = IllegalStateException("private role failure"); runCurrent()
        f.transfer()
        assertFalse(f.add.state.value.canSubmit)
        f.add.submit(); runCurrent()
        assertTrue(f.use.entries.stored.isEmpty())
        f.roles.failure = null; f.add.retryFeeCategory(); runCurrent()
        assertTrue(f.add.state.value.canSubmit)
        f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        assertEquals(Money.of("6", "USD"), (f.use.entries.stored.single() as Transaction.Transfer).fee)
    }

    @Test fun `zero and blank fee bypass unavailable role lookup`() = runTest(dispatcher) {
        listOf("0", "").forEach { value ->
            val f = fixture(); f.roles.failure = IllegalStateException("private role failure"); runCurrent()
            f.transfer(); f.add.setFeeValue(value)
            assertTrue(f.add.state.value.canSubmit)
            f.add.submit(); runCurrent()
            assertNull(f.add.state.value.error)
            assertNull((f.use.entries.stored.single() as Transaction.Transfer).fee)
        }
    }

    @Test fun `untouched transfer fee follows source default only when source actually changes`() =
        runTest(dispatcher) {
            val f = fixture(); runCurrent(); f.transfer()
            f.add.selectAccount(1)
            assertEquals("2", f.add.state.value.fee.valueInput)
            f.add.selectAccount(4)
            assertEquals("", f.add.state.value.fee.valueInput)
            f.add.selectAccount(1)
            assertEquals("2", f.add.state.value.fee.valueInput)
        }

    @Test fun `switching to a kind without fee clears entry fee choices`() = runTest(dispatcher) {
        val f = fixture(); runCurrent(); f.transfer(); f.add.selectType(TransactionType.REFUND)
        assertEquals("", f.add.state.value.fee.valueInput)
        assertNull(f.add.state.value.fee.amount)
        f.add.selectType(TransactionType.EXPENSE)
        assertEquals("", f.add.state.value.fee.valueInput)
    }

    @Test fun `large transfer receipt agrees with the real save rounded pair`() = runTest(dispatcher) {
        val f = fixture()
        f.rates.upsert(org.aristonis.mywallet.domain.model.ExchangeRate("EUR", BigDecimal("3")))
        runCurrent(); f.transfer(); f.add.selectDestAccount(3); f.add.selectFeeMode(FeeMode.FIXED)
        f.add.setFeeValue("1"); f.add.setAmount("30000000001")
        val preview = f.add.state.value.fee.accountMovement
        f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        val saved = f.use.entries.stored.single() as Transaction.Transfer
        assertEquals(Money.of("9999999999.99", "EUR"), saved.destAmount)
        assertEquals(saved.destAmount, preview)
    }

    @Test fun `clearing a visible transfer rate removes net preview and refuses direct save`() = runTest(dispatcher) {
        val f = fixture()
        f.rates.upsert(org.aristonis.mywallet.domain.model.ExchangeRate("EUR", BigDecimal("1.2")))
        runCurrent(); f.transfer(); f.add.selectDestAccount(3); f.add.setRate("EUR", "")
        assertNull(f.add.state.value.fee.accountMovement)
        assertFalse(f.add.state.value.canSubmit)
        f.add.submit(); runCurrent()
        assertTrue(f.use.entries.stored.isEmpty())
    }

    @Test fun `a rate from another pair is ignored after the destination changes`() = runTest(dispatcher) {
        val f = fixture(); runCurrent(); f.transfer(); f.add.selectDestAccount(3); f.add.setRate("EUR", "wrong")
        f.add.selectDestAccount(2)
        assertNull(f.add.state.value.fee.error)
        assertTrue(f.add.state.value.canSubmit)
        f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        assertEquals(Money.of("294", "USD"), (f.use.entries.stored.single() as Transaction.Transfer).destAmount)
    }

    private fun expense(fee: String = "7") = Transaction.Expense(7, 1, Money.of("50", "USD"), 4,
        date = date, fee = Money.of(fee, "USD"), feeCategoryId = 4)

    @Test fun `editing fee mode saves its new fee without changing principal`() = runTest(dispatcher) {
        val f = fixture(listOf(expense())); f.edit.load(7); runCurrent()
        f.edit.selectFeeMode(FeeMode.PERCENT)
        assertEquals("", f.edit.state.value.fee.valueInput)
        f.edit.setFeeValue("2"); f.edit.selectFeeCategory(5)
        assertEquals(Money.of("51", "USD"), f.edit.state.value.fee.accountMovement)
        f.edit.submit(); runCurrent()
        assertNull(f.edit.state.value.error)
        val saved = f.use.entries.stored.single() as Transaction.Expense
        assertEquals(Money.of("50", "USD"), saved.amount)
        assertEquals(Money.of("1", "USD"), saved.fee)
        assertEquals(5L, saved.feeCategoryId)
    }

    @Test fun `edit rejects invalid fee categories and null restores default`() = runTest(dispatcher) {
        val f = fixture(listOf(expense())); f.edit.load(7); runCurrent()
        listOf(3L, 6L, 404L).forEach { id ->
            f.edit.selectFeeCategory(id)
            assertEquals(4L, f.edit.state.value.fee.categoryId)
        }
        f.edit.selectFeeCategory(null); f.edit.submit(); runCurrent()
        assertNull(f.edit.state.value.error)
        val saved = f.use.entries.stored.single() as Transaction.Expense
        assertEquals(Money.of("7", "USD"), saved.fee)
        assertEquals(5L, saved.feeCategoryId)
    }

    @Test fun `add rejects invalid fee choices and null restores the default category`() = runTest(dispatcher) {
        val f = fixture(); runCurrent(); f.transfer(); f.add.selectFeeCategory(4)
        listOf(3L, 6L, 404L).forEach { id ->
            f.add.selectFeeCategory(id)
            assertEquals(4L, f.add.state.value.fee.categoryId)
        }
        f.add.selectFeeCategory(null); f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        assertEquals(5L, (f.use.entries.stored.single() as Transaction.Transfer).feeCategoryId)
    }

    @Test fun `edited fee refuses a failed role read and retry saves owner changes`() = runTest(dispatcher) {
        val original = expense()
        val f = fixture(listOf(original)); f.roles.failure = IllegalStateException("private role failure")
        f.edit.load(7); runCurrent(); f.edit.setFeeValue("3")
        assertFalse(f.edit.state.value.canSubmit)
        f.edit.submit(); runCurrent()
        assertEquals(original, f.use.entries.stored.single())
        f.roles.failure = null; f.edit.retryFeeCategory(); runCurrent()
        assertTrue(f.edit.state.value.canSubmit)
        f.edit.submit(); runCurrent()
        assertNull(f.edit.state.value.error)
        assertEquals(Money.of("3", "USD"), (f.use.entries.stored.single() as Transaction.Expense).fee)
    }

    @Test fun `negative edited fee refuses saving and zero removes the recorded fee`() = runTest(dispatcher) {
        val original = expense()
        val f = fixture(listOf(original)); f.edit.load(7); runCurrent(); f.edit.setFeeValue("-1")
        assertFalse(f.edit.state.value.canSubmit)
        f.edit.submit(); runCurrent()
        assertEquals(original, f.use.entries.stored.single())
        f.edit.setFeeValue("0")
        assertTrue(f.edit.state.value.canSubmit)
        f.edit.submit(); runCurrent()
        assertNull(f.edit.state.value.error)
        val saved = f.use.entries.stored.single() as Transaction.Expense
        assertNull(saved.fee)
        assertEquals(Money.of("50", "USD"), saved.amount)
        assertFalse(saved.toParts().legs.any { it.role == LegRole.FEE })
    }

    @Test fun `moving an edited fixed fee clears old digits before entering its new currency`() = runTest(dispatcher) {
        val f = fixture(listOf(expense()))
        f.rates.upsert(org.aristonis.mywallet.domain.model.ExchangeRate("EUR", BigDecimal("2")))
        f.edit.load(7); runCurrent(); f.edit.selectAccount(3)
        assertEquals("", f.edit.state.value.fee.valueInput)
        assertNull(f.edit.state.value.fee.amount)
        f.edit.setFeeValue("1"); f.edit.submit(); runCurrent()
        assertNull(f.edit.state.value.error)
        val saved = f.use.entries.stored.single() as Transaction.Expense
        assertEquals(Money.of("50", "EUR"), saved.amount)
        assertEquals(Money.of("1", "EUR"), saved.fee)
        assertEquals(4L, saved.feeCategoryId)
        assertEquals(Money.of("-51", "EUR"), saved.toParts().legs.single { it.role == LegRole.ACCOUNT }.amount)
    }

    @Test fun `acknowledging a zero fee transfer restores the default for the next entry`() = runTest(dispatcher) {
        val f = fixture(); runCurrent(); f.transfer(); f.add.setFeeValue("0"); f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        f.add.acknowledgeSaved()
        assertEquals("2", f.add.state.value.fee.valueInput)
        f.add.selectDestAccount(2); f.add.setAmount("300"); f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        val saved = f.use.entries.stored.map { it as Transaction.Transfer }
        assertNull(saved.first().fee)
        assertEquals(Money.of("6", "USD"), saved.last().fee)
        assertEquals(Money.of("294", "USD"), saved.last().destAmount)
    }

    @Test fun `choosing transfer before accounts arrive applies the first source default`() = runTest(dispatcher) {
        val f = fixture(); f.transfer(); runCurrent()
        assertEquals(1L, f.add.state.value.selectedAccountId)
        assertEquals("2", f.add.state.value.fee.valueInput)
        f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        assertEquals(Money.of("294", "USD"), (f.use.entries.stored.single() as Transaction.Transfer).destAmount)
    }

    @Test fun `fee waits for a pending role read and saves after the read completes`() = runTest(dispatcher) {
        val f = fixture()
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.roles.beforeRead = { ready.await() }
        runCurrent(); f.transfer()
        assertFalse(f.add.state.value.canSubmit)
        f.add.submit(); runCurrent()
        assertTrue(f.use.entries.stored.isEmpty())
        ready.complete(Unit); runCurrent()
        assertTrue(f.add.state.value.canSubmit)
        f.add.submit(); runCurrent()
        assertNull(f.add.state.value.error)
        assertEquals(Money.of("6", "USD"), (f.use.entries.stored.single() as Transaction.Transfer).fee)
    }

    @Test fun `moving expense currency with an empty fee removes the old fee`() = runTest(dispatcher) {
        val f = fixture(listOf(expense()))
        f.rates.upsert(org.aristonis.mywallet.domain.model.ExchangeRate("EUR", BigDecimal("2")))
        f.edit.load(7); runCurrent(); f.edit.selectAccount(3); f.edit.submit(); runCurrent()
        assertNull(f.edit.state.value.error)
        val saved = f.use.entries.stored.single() as Transaction.Expense
        assertEquals(7L, saved.id)
        assertEquals(Money.of("50", "EUR"), saved.amount)
        assertNull(saved.fee)
        assertNull(saved.feeCategoryId)
        assertFalse(saved.toParts().legs.any { it.role == LegRole.FEE })
    }

}
