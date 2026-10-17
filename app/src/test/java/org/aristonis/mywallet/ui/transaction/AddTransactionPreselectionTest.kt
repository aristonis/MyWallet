package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import java.math.BigDecimal
import org.aristonis.mywallet.domain.model.FeeRule
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.SuggestCounterparties
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.navigation.ARG_ACCOUNT_ID
import org.aristonis.mywallet.ui.navigation.ARG_PAY_CARD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The add form can open with an account already chosen (from an account's page) or as a payment into
 * a credit card. The form may only ever use the account it was told about: an archived or unknown id
 * must leave the choice empty rather than silently become another account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionPreselectionTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun account(id: Long, typeKey: String = "cash", archived: Boolean = false) = Account(
        id = id, name = "acct-$id", typeKey = typeKey, currencyCode = "USD",
        openingBalance = Money.of("0", "USD"), archived = archived,
    )

    private class Fixture(accounts: List<Account>, accountId: Long? = null, payCard: Boolean? = null) {
        val accountRepo = FakeAccountRepository(accounts)
        val categoryRepo = FakeCategoryRepository(listOf(
            Category(id = 1, name = "Salary", kind = CategoryKind.INCOME),
            Category(id = 2, name = "Food", kind = CategoryKind.EXPENSE)))
        private val currencyRepo = FakeCurrencyRepository(listOf(Currency("USD", "$", 2)))
        private val rateRepo = FakeRateRepository()
        private val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
        val use = EntryUseCases(accountRepo, categoryRepo, FakeEntryRepository(), currencyRepo, rateRepo, settingsRepo)
        private val args: Map<String, Any> = buildMap {
            accountId?.let { put(ARG_ACCOUNT_ID, it) }
            payCard?.let { put(ARG_PAY_CARD, it) }
        }
        val viewModel = AddTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            saveEntry = use.saveEntry,
            today = TodayProvider { LocalDate.of(2026, 7, 3) },
            moneyParser = MoneyParser(Locale.US),
            fx = FakeFxRepository(currencyRepo, rateRepo, settingsRepo),
            settings = settingsRepo,
            moneyFormatter = MoneyFormatter(Locale.US),
            suggestCounterparties = SuggestCounterparties(use.debts),
            debts = use.debts,
            deductionItems = FakeDeductionItemRepository(),
            categoryRoles = FakeFormRoles(),
            savedStateHandle = SavedStateHandle(args),
        )
        val state get() = viewModel.state.value
        val stored get() = use.entries.stored
    }

    private val cash1 = account(1)
    private val cash2 = account(2)
    private val card3 = account(3, typeKey = "credit_card")

    @Test
    fun `an active account id is selected on the default expense form instead of the first account`() = runTest {
        val f = Fixture(listOf(cash1, cash2), accountId = 2)
        advanceUntilIdle()

        assertEquals(2L, f.state.selectedAccountId)
        assertEquals(TransactionType.EXPENSE, f.state.type)
    }

    @Test
    fun `an archived account id leaves the account unselected and blocks the save`() = runTest {
        val f = Fixture(listOf(cash1, account(2, archived = true)), accountId = 2)
        advanceUntilIdle()
        f.viewModel.selectCategory(2)
        f.viewModel.setAmount("10")

        assertNull(f.state.selectedAccountId)
        assertFalse(f.state.canSubmit)
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(UiMessage.AccountRequired, f.state.error)
        assertTrue(f.stored.isEmpty())
    }

    @Test
    fun `an account id that does not exist leaves the account unselected and blocks the save`() = runTest {
        val f = Fixture(listOf(cash1, cash2), accountId = 99)
        advanceUntilIdle()
        f.viewModel.selectCategory(2)
        f.viewModel.setAmount("10")

        assertNull(f.state.selectedAccountId)
        assertFalse(f.state.canSubmit)
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(UiMessage.AccountRequired, f.state.error)
        assertTrue(f.stored.isEmpty())
    }

    @Test
    fun `a preselected account stays selected after the type changes to income`() = runTest {
        val f = Fixture(listOf(cash1, cash2), accountId = 2)
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.INCOME)
        advanceUntilIdle()

        assertEquals(TransactionType.INCOME, f.state.type)
        assertEquals(2L, f.state.selectedAccountId)
    }

    @Test
    fun `a preselected account is kept for every type and never replaced by another`() = runTest {
        // Every type accepts every active account, so the preselection must survive any type switch.
        val f = Fixture(listOf(cash1, cash2), accountId = 2)
        advanceUntilIdle()

        TransactionType.entries.forEach { type ->
            f.viewModel.selectType(type)
            advanceUntilIdle()
            assertEquals(type.toString(), 2L, f.state.selectedAccountId)
        }
    }

    @Test
    fun `pay card opens a transfer into the card with the source left for the user`() = runTest {
        val f = Fixture(listOf(cash1, cash2, card3), accountId = 3, payCard = true)
        advanceUntilIdle()
        f.viewModel.setAmount("25")

        assertEquals(TransactionType.TRANSFER, f.state.type)
        assertEquals(3L, f.state.destAccountId)
        assertNull(f.state.selectedAccountId)
        assertFalse(f.state.canSubmit)

        f.viewModel.selectAccount(1)
        assertTrue(f.state.canSubmit)
    }

    @Test
    fun `pay card saves one transfer into the card and no expense`() = runTest {
        val f = Fixture(listOf(cash1, cash2, card3), accountId = 3, payCard = true)
        advanceUntilIdle()

        f.viewModel.selectAccount(1)
        f.viewModel.setAmount("25")
        f.viewModel.submit()
        advanceUntilIdle()

        val saved = f.stored.single()
        assertTrue(saved is Transaction.Transfer)
        saved as Transaction.Transfer
        assertEquals(1L, saved.sourceAccountId)
        assertEquals(3L, saved.destAccountId)
        assertTrue(f.stored.none { it is Transaction.Expense })
    }

    @Test
    fun `pay card does not pre-fill the source account's default transfer fee`() = runTest {
        val bank = Account(
            id = 1, name = "bank", typeKey = "bank", currencyCode = "USD",
            openingBalance = Money.of("0", "USD"), defaultFee = FeeRule(FeeMode.FIXED, BigDecimal("2")),
        )
        val f = Fixture(listOf(bank, cash2, card3), accountId = 3, payCard = true)
        advanceUntilIdle()

        f.viewModel.selectAccount(1)
        f.viewModel.setAmount("25")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals("", f.state.fee.valueInput)
        val saved = f.stored.single() as Transaction.Transfer
        assertNull(saved.fee)
    }

    @Test
    fun `an ordinary transfer still starts from the source account's default fee`() = runTest {
        val bank = Account(
            id = 1, name = "bank", typeKey = "bank", currencyCode = "USD",
            openingBalance = Money.of("0", "USD"), defaultFee = FeeRule(FeeMode.FIXED, BigDecimal("2")),
        )
        val f = Fixture(listOf(bank, cash2, card3))
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.TRANSFER)
        f.viewModel.selectAccount(1)

        assertEquals("2", f.state.fee.valueInput)
    }

    @Test
    fun `choosing the card itself as the source keeps pay card unsubmittable`() = runTest {
        val f = Fixture(listOf(cash1, cash2, card3), accountId = 3, payCard = true)
        advanceUntilIdle()
        f.viewModel.setAmount("25")

        f.viewModel.selectAccount(3)

        assertFalse(f.state.canSubmit)
    }

    @Test
    fun `pay card for an archived card leaves the destination empty`() = runTest {
        val f = Fixture(listOf(cash1, cash2, account(3, typeKey = "credit_card", archived = true)),
            accountId = 3, payCard = true)
        advanceUntilIdle()

        assertNull(f.state.destAccountId)
        assertFalse(f.state.canSubmit)
    }

    @Test
    fun `pay card for a missing card leaves the destination empty`() = runTest {
        val f = Fixture(listOf(cash1, cash2), accountId = 99, payCard = true)
        advanceUntilIdle()

        assertNull(f.state.destAccountId)
        assertFalse(f.state.canSubmit)
    }

    @Test
    fun `without the new arguments the first active account is selected on an expense form`() = runTest {
        val f = Fixture(listOf(account(1, archived = true), cash2, card3))
        advanceUntilIdle()

        assertEquals(2L, f.state.selectedAccountId)
        assertEquals(TransactionType.EXPENSE, f.state.type)
        assertNull(f.state.destAccountId)
    }
}
