package org.aristonis.mywallet.ui.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.LocaleDefaults
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.FeeRule
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.CreateAccount
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class AddAccountViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val usd = Currency("USD", "$", 2)
    private val eur = Currency("EUR", "€", 2)

    private class Fixture(
        currencies: List<Currency>,
        localeCurrency: String? = null,
        locale: Locale = Locale.US,
        upsertGate: CompletableDeferred<Unit>? = null,
    ) {
        val currencyRepo = FakeCurrencyRepository(currencies)
        val accountRepo = FakeAccountRepository()
        private val gatedAccountRepo = object : AccountRepository by accountRepo {
            override suspend fun upsert(account: Account): Long {
                upsertGate?.await()
                return accountRepo.upsert(account)
            }
        }
        val viewModel = AddAccountViewModel(
            currencies = currencyRepo,
            createAccount = CreateAccount(currencyRepo, gatedAccountRepo),
            localeDefaults = LocaleDefaults(localeCurrency),
            moneyParser = MoneyParser(locale),
        )
    }

    @Test
    fun localizedFeeInputAndZeroAreSavedWhileNoneIgnoresHiddenInvalidText() = runTest {
        val f = Fixture(listOf(usd), locale = Locale.GERMANY)
        advanceUntilIdle()
        f.viewModel.setName("Wallet")
        f.viewModel.selectFeeMode(FeeMode.PERCENT)
        f.viewModel.setFeeValue("2,125")
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(FeeRule(FeeMode.PERCENT, BigDecimal("2.125")), f.accountRepo.upserted.single().defaultFee)
        f.viewModel.acknowledgeCreated()
        f.viewModel.setName("Zero")
        f.viewModel.selectFeeMode(FeeMode.FIXED)
        f.viewModel.setFeeValue("0")
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(FeeRule(FeeMode.FIXED, BigDecimal.ZERO), f.accountRepo.upserted.last().defaultFee)
        f.viewModel.acknowledgeCreated()
        f.viewModel.setName("Cash")
        f.viewModel.setFeeValue("invalid")
        f.viewModel.selectFeeMode(null)
        f.viewModel.submit()
        advanceUntilIdle()
        assertNull(f.accountRepo.upserted.last().defaultFee)
    }

    @Test
    fun aPercentDefaultFeeIsSavedExactlyAndResetAfterCreation() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()
        f.viewModel.setName("Wallet")
        f.viewModel.selectFeeMode(FeeMode.PERCENT)
        f.viewModel.setFeeValue("2.125")
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(FeeRule(FeeMode.PERCENT, BigDecimal("2.125")), f.accountRepo.upserted.single().defaultFee)
        f.viewModel.acknowledgeCreated()
        assertNull(f.viewModel.state.value.feeMode)
        assertEquals("", f.viewModel.state.value.feeValueInput)
    }

    @Test
    fun aFixedDefaultFeeUsesTheAccountCurrencyAndBlankMeansNone() = runTest {
        val f = Fixture(listOf(usd, eur))
        advanceUntilIdle()
        f.viewModel.setName("Wallet")
        f.viewModel.selectCurrency("EUR")
        f.viewModel.selectFeeMode(FeeMode.FIXED)
        f.viewModel.setFeeValue("1.25")
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(FeeRule(FeeMode.FIXED, BigDecimal("1.25")), f.accountRepo.upserted.single().defaultFee)
        assertEquals("EUR", f.accountRepo.upserted.single().currencyCode)
        f.viewModel.acknowledgeCreated()
        f.viewModel.setName("Cash")
        f.viewModel.selectFeeMode(FeeMode.FIXED)
        f.viewModel.setFeeValue(" ")
        f.viewModel.submit()
        advanceUntilIdle()
        assertNull(f.accountRepo.upserted.last().defaultFee)
    }

    @Test
    fun invalidDefaultFeesNeverCreateAnAccount() = runTest {
        val expected = listOf(
            Triple(FeeMode.PERCENT, "-1", UiMessage.FeeValueNegative),
            Triple(FeeMode.FIXED, "-0.01", UiMessage.FeeValueNegative),
            Triple(FeeMode.PERCENT, "1.25x", UiMessage.NotANumber),
            Triple(FeeMode.PERCENT, "1,25", UiMessage.NotANumber),
            Triple(FeeMode.PERCENT, "1E40", UiMessage.AmountOutOfRange),
            Triple(FeeMode.PERCENT, "100.001", UiMessage.FeePercentAboveHundred),
        )
        for ((mode, input, message) in expected) {
            val f = Fixture(listOf(usd))
            advanceUntilIdle()
            f.viewModel.setName("Wallet")
            f.viewModel.selectFeeMode(mode)
            f.viewModel.setFeeValue(input)
            f.viewModel.submit()
            advanceUntilIdle()
            assertEquals("$mode $input", message, f.viewModel.state.value.error)
            assertEquals("$mode $input", AccountInputField.DEFAULT_FEE, f.viewModel.state.value.errorField)
            assertTrue("$mode $input", f.accountRepo.upserted.isEmpty())
        }
    }

    @Test
    fun aPercentOfExactlyOneHundredAndAFixedFeeAboveOneHundredAreSaved() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()
        f.viewModel.setName("All of it")
        f.viewModel.selectFeeMode(FeeMode.PERCENT)
        f.viewModel.setFeeValue("100.00")
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(FeeRule(FeeMode.PERCENT, BigDecimal("100.00")), f.accountRepo.upserted.single().defaultFee)
        f.viewModel.acknowledgeCreated()
        f.viewModel.setName("Wire")
        f.viewModel.selectFeeMode(FeeMode.FIXED)
        f.viewModel.setFeeValue("250")
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(FeeRule(FeeMode.FIXED, BigDecimal("250")), f.accountRepo.upserted.last().defaultFee)
    }

    @Test
    fun changingTheFeeModeClearsTheTypedValue() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()
        f.viewModel.selectFeeMode(FeeMode.PERCENT)
        f.viewModel.setFeeValue("2.125")
        f.viewModel.selectFeeMode(FeeMode.PERCENT)
        assertEquals("2.125", f.viewModel.state.value.feeValueInput)
        f.viewModel.selectFeeMode(FeeMode.FIXED)
        assertEquals("", f.viewModel.state.value.feeValueInput)
        f.viewModel.setFeeValue("3.50")
        f.viewModel.selectFeeMode(null)
        assertEquals("", f.viewModel.state.value.feeValueInput)
        f.viewModel.selectFeeMode(FeeMode.FIXED)
        f.viewModel.setFeeValue("3.50")
        f.viewModel.selectFeeMode(FeeMode.PERCENT)
        assertEquals("", f.viewModel.state.value.feeValueInput)
    }

    @Test
    fun changingTheCurrencyClearsAFixedFeeButKeepsAPercent() = runTest {
        val f = Fixture(listOf(usd, eur))
        advanceUntilIdle()
        f.viewModel.selectCurrency("USD")
        f.viewModel.selectFeeMode(FeeMode.FIXED)
        f.viewModel.setFeeValue("3.50")
        f.viewModel.selectCurrency("USD")
        assertEquals("3.50", f.viewModel.state.value.feeValueInput)
        f.viewModel.selectCurrency("EUR")
        assertEquals("", f.viewModel.state.value.feeValueInput)
        assertEquals(FeeMode.FIXED, f.viewModel.state.value.feeMode)
        f.viewModel.selectFeeMode(FeeMode.PERCENT)
        f.viewModel.setFeeValue("2")
        f.viewModel.selectCurrency("USD")
        assertEquals("2", f.viewModel.state.value.feeValueInput)
    }

    @Test
    fun init_defaultsCurrencyToDeviceLocale() = runTest {
        val f = Fixture(listOf(usd, eur), localeCurrency = "EUR")
        advanceUntilIdle()

        assertEquals("EUR", f.viewModel.state.value.selectedCurrencyCode)
    }

    @Test
    fun submit_validInput_createsAccount() = runTest {
        val f = Fixture(listOf(usd, eur))
        advanceUntilIdle()

        f.viewModel.selectCurrency("EUR")
        f.viewModel.setName("Euro Savings")
        f.viewModel.selectAccountType("savings")
        f.viewModel.setOpeningBalance("500")
        f.viewModel.submit()
        advanceUntilIdle()

        val account = f.accountRepo.upserted.single()
        assertEquals("Euro Savings", account.name)
        assertEquals("savings", account.typeKey)
        assertEquals("EUR", account.currencyCode)
        assertEquals(Money.of("500", "EUR"), account.openingBalance)
        assertTrue(f.viewModel.state.value.created)
    }

    @Test
    fun submit_blankName_failsLoudWithoutCreating() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        f.viewModel.setName("   ")
        f.viewModel.submit()
        advanceUntilIdle()

        assertNotNull(f.viewModel.state.value.error)
        assertNull(f.viewModel.state.value.errorField)
        assertFalse(f.viewModel.state.value.created)
        assertTrue(f.accountRepo.upserted.isEmpty())
    }

    @Test
    fun acknowledgeCreated_clearsCreatedFlagAndResetsForm() = runTest {
        // The VM is Activity-scoped (no nav back-stack), so a retained created==true would bounce a
        // re-opened AddAccount straight back to Home — blocking a second account. acknowledgeCreated
        // resets the flag (and clears the form) after the screen has navigated away.
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        f.viewModel.setName("Cash")
        f.viewModel.setOpeningBalance("100")
        f.viewModel.submit()
        advanceUntilIdle()
        assertTrue(f.viewModel.state.value.created)

        f.viewModel.acknowledgeCreated()

        val s = f.viewModel.state.value
        assertFalse(s.created)
        assertEquals("", s.name)
        assertEquals("", s.openingBalanceInput)
    }

    @Test
    fun submit_invalidOpeningBalance_failsLoud() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        f.viewModel.setName("Cash")
        f.viewModel.setOpeningBalance("not-a-number")
        f.viewModel.submit()
        advanceUntilIdle()

        assertNotNull(f.viewModel.state.value.error)
        assertEquals(AccountInputField.OPENING_BALANCE, f.viewModel.state.value.errorField)
        assertFalse(f.viewModel.state.value.created)
        assertTrue(f.accountRepo.upserted.isEmpty())
    }

    @Test
    fun correctingInvalidOpeningBalanceClearsErrorAndKeepsDraftForRetry() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()
        f.viewModel.setName("Cash")
        f.viewModel.setOpeningBalance("not-a-number")
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(UiMessage.NotANumber, f.viewModel.state.value.error)
        assertTrue(f.accountRepo.upserted.isEmpty())

        f.viewModel.setOpeningBalance("25.50")

        assertNull(f.viewModel.state.value.error)
        assertEquals("Cash", f.viewModel.state.value.name)
        assertEquals("25.50", f.viewModel.state.value.openingBalanceInput)
        f.viewModel.submit()
        advanceUntilIdle()
        assertTrue(f.viewModel.state.value.created)
        assertEquals(Money.of("25.50", "USD"), f.accountRepo.upserted.single().openingBalance)
    }

    @Test
    fun changingAccountTypeClearsInvalidCreditLimitErrorAndKeepsDraftForRetry() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()
        f.viewModel.setName("Cash")
        f.viewModel.selectAccountType("credit_card")
        f.viewModel.setCreditLimit("0")
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(UiMessage.AmountNotPositive, f.viewModel.state.value.error)
        assertEquals(AccountInputField.CREDIT_LIMIT, f.viewModel.state.value.errorField)
        assertTrue(f.accountRepo.upserted.isEmpty())

        f.viewModel.selectAccountType("cash")

        assertNull(f.viewModel.state.value.error)
        assertEquals("Cash", f.viewModel.state.value.name)
        f.viewModel.submit()
        advanceUntilIdle()
        assertTrue(f.viewModel.state.value.created)
        assertNull(f.accountRepo.upserted.single().creditLimit)
    }

    @Test
    fun pendingSaveKeepsSubmittedDraftAndRejectsDuplicateSubmit() = runTest {
        val upsertGate = CompletableDeferred<Unit>()
        val f = Fixture(listOf(usd, eur), upsertGate = upsertGate)
        advanceUntilIdle()
        f.viewModel.setName("Cash")
        f.viewModel.setOpeningBalance("25")
        f.viewModel.submit()
        f.viewModel.submit()
        runCurrent()
        val pendingDraft = f.viewModel.state.value
        assertTrue(pendingDraft.isSubmitting)

        f.viewModel.setName("Changed")
        f.viewModel.selectCurrency("EUR")
        f.viewModel.selectAccountType("credit_card")
        f.viewModel.setOpeningBalance("99")
        f.viewModel.setCreditLimit("100")
        f.viewModel.selectFeeMode(FeeMode.FIXED)
        f.viewModel.setFeeValue("5")
        f.viewModel.submit()
        assertEquals(pendingDraft, f.viewModel.state.value)

        upsertGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(f.viewModel.state.value.created)
        assertEquals("Cash", f.accountRepo.upserted.single().name)
        assertEquals(Money.of("25", "USD"), f.accountRepo.upserted.single().openingBalance)
    }

    private fun creditCardForm(f: Fixture, limit: String, currency: String = "USD") {
        f.viewModel.selectCurrency(currency)
        f.viewModel.setName("Visa")
        f.viewModel.selectAccountType("credit_card")
        f.viewModel.setCreditLimit(limit)
    }

    @Test
    fun creditLimitField_isShownOnlyForOwedWhenNegativeTypes() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        assertFalse(f.viewModel.state.value.showsCreditLimit) // default type is cash
        f.viewModel.selectAccountType("credit_card")
        assertTrue(f.viewModel.state.value.showsCreditLimit)
        f.viewModel.selectAccountType("card")
        assertFalse(f.viewModel.state.value.showsCreditLimit)
    }

    @Test
    fun submit_creditCardWithLimit_passesTheLimitInTheAccountCurrency() = runTest {
        val f = Fixture(listOf(usd, eur))
        advanceUntilIdle()

        creditCardForm(f, "2000")
        f.viewModel.submit()
        advanceUntilIdle()

        val account = f.accountRepo.upserted.single()
        assertEquals("credit_card", account.typeKey)
        assertEquals(Money.of("2000", "USD"), account.creditLimit)
        assertTrue(f.viewModel.state.value.created)
    }

    @Test
    fun submit_creditCardInOtherCurrency_limitFollowsTheSelectedCurrency() = runTest {
        val f = Fixture(listOf(usd, eur))
        advanceUntilIdle()

        creditCardForm(f, "1500", currency = "EUR")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(Money.of("1500", "EUR"), f.accountRepo.upserted.single().creditLimit)
    }

    @Test
    fun submit_blankLimit_createsCreditCardWithNoLimit() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        creditCardForm(f, "   ")
        f.viewModel.submit()
        advanceUntilIdle()

        val account = f.accountRepo.upserted.single()
        assertEquals("credit_card", account.typeKey)
        assertNull(account.creditLimit)
    }

    @Test
    fun submit_zeroLimit_showsNotPositiveMessage_andCreatesNothing() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        creditCardForm(f, "0")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiMessage.AmountNotPositive, f.viewModel.state.value.error)
        assertFalse(f.viewModel.state.value.created)
        assertTrue(f.accountRepo.upserted.isEmpty())
    }

    @Test
    fun submit_negativeLimit_showsNotPositiveMessage_andCreatesNothing() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        creditCardForm(f, "-5")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiMessage.AmountNotPositive, f.viewModel.state.value.error)
        assertTrue(f.accountRepo.upserted.isEmpty())
    }

    @Test
    fun submit_unreadableLimit_isRefusedWithTheNumberMessage() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        creditCardForm(f, "lots")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiMessage.NotANumber, f.viewModel.state.value.error)
        assertTrue(f.accountRepo.upserted.isEmpty())
    }

    @Test
    fun submit_limitWithExtraDecimals_isHandledLikeTheOpeningBalance() = runTest {
        // No amount path refuses or rounds to the currency's decimals today (the parser only guards the
        // extreme scale), so the limit is kept exactly as typed, the same as the opening balance is.
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        creditCardForm(f, "2000.123")
        f.viewModel.setOpeningBalance("-10.456")
        f.viewModel.submit()
        advanceUntilIdle()

        val account = f.accountRepo.upserted.single()
        assertEquals(Money.of("2000.123", "USD"), account.creditLimit)
        assertEquals(Money.of("-10.456", "USD"), account.openingBalance)
    }

    @Test
    fun submit_switchingFromCreditCardToCash_createsAccountWithNoLimit() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        creditCardForm(f, "2000")
        f.viewModel.selectAccountType("cash")
        f.viewModel.submit()
        advanceUntilIdle()

        val account = f.accountRepo.upserted.single()
        assertEquals("cash", account.typeKey)
        assertNull(account.creditLimit)
    }

    @Test
    fun submit_hiddenInvalidLimit_isIgnoredWhenTypeHasNoLimit() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        creditCardForm(f, "0")
        f.viewModel.selectAccountType("cash")
        f.viewModel.submit()
        advanceUntilIdle()

        assertTrue(f.viewModel.state.value.created)
        assertNull(f.accountRepo.upserted.single().creditLimit)
    }
}

// In-memory port fakes shared with the other account VM tests live in AccountTestFakes.kt.
