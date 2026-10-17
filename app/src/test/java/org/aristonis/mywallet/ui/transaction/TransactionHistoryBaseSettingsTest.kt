package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.HealthLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/** A settings emission updates the history's base label without using current market quotes. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionHistoryBaseSettingsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `base settings stream changes row approximation without reloading latest rates`() = runTest(dispatcher) {
        val settings = FakeSettingsRepository(Settings("USD"))
        val entry = Transaction.Income(
            id = 1, accountId = 1, categoryId = 2, amount = Money.of("100", "EUR"),
            date = LocalDate.of(2026, 10, 12), rateToBase = BigDecimal("1.25"),
        )
        val viewModel = TransactionsListViewModel(
            debts = FakeDebtRepository(),
            entries = FakeEntryRepository(listOf(entry)), accounts = FakeAccountRepository(),
            categories = FakeCategoryRepository(),
            currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))),
            settings = settings, observeLedgerHealth = HealthLedger().health(),
            moneyFormatter = MoneyFormatter(Locale.US), today = { entry.date },
            savedStateHandle = SavedStateHandle(), defaultDispatcher = dispatcher,
            errors = RecordingErrorReporter(),
        )
        watch(viewModel)
        advanceUntilIdle()
        assertEquals("125.00 USD", viewModel.state.value.rows.single().countedBaseAmountDisplay)
        settings.save(Settings("EUR"))
        advanceUntilIdle()
        assertNull(viewModel.state.value.rows.single().countedBaseAmountDisplay)
    }
}
