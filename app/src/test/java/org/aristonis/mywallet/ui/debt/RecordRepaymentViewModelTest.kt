package org.aristonis.mywallet.ui.debt

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

@OptIn(ExperimentalCoroutinesApi::class)
class RecordRepaymentViewModelTest {
    private val work = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(work)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun interestCategoryStartsAtTheDebtSideRoleAndRejectsMissingWrongOrChildBindings() = runTest(work) {
        val interestOut = Category(5, "Loan interest", CategoryKind.EXPENSE)
        val cases = listOf(
            RepaymentRoleCase("valid", listOf(interestOut), mapOf(CategoryRole.INTEREST_OUT to interestOut.id), interestOut.id),
            RepaymentRoleCase("missing", listOf(interestOut), emptyMap(), null),
            RepaymentRoleCase("wrong kind", listOf(interestOut.copy(kind = CategoryKind.INCOME)), mapOf(CategoryRole.INTEREST_OUT to interestOut.id), null),
            RepaymentRoleCase("child", listOf(interestOut.copy(parentId = 99)), mapOf(CategoryRole.INTEREST_OUT to interestOut.id), null),
        )

        cases.forEach { case ->
            val vm = repaymentRig(categories = case.categories, roles = case.roles).repayment().also { watch(it) }
            try {
                vm.load(debtId = 1)
                runCurrent()

                assertEquals(case.name, case.expectedCategoryId, vm.state.value.interestCategoryId)
            } finally {
                vm.viewModelScope.cancel()
            }
        }
    }

    @Test fun explicitCrossCurrencyPrincipalsUseTheTypedPairRateWithoutABaseFxRate() = runTest(work) {
        val rig = repaymentRig(account = account(currency = "EUR"))
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1)
            runCurrent()
            assertTrue(vm.state.value.rateRequired)

            vm.setPrincipal("100")
            vm.setAccountPrincipal("80")
            vm.setRate("EUR", "1.25")
            assertFalse(vm.state.value.rateRequired)
            vm.submit()
            runCurrent()

            val saved = rig.entries.stored.single { it is Transaction.Repayment } as Transaction.Repayment
            assertMoney("100", "USD", saved.principal)
            assertMoney("80", "EUR", saved.accountAmount)
            assertEquals(0, BigDecimal("1.25").compareTo(saved.pairRate))
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun payTheRestDelegatesANullPrincipalForTheFreshDebtRemaining() = runTest(work) {
        val rig = repaymentRig()
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1)
            runCurrent()

            vm.payTheRest(true)
            assertTrue(vm.state.value.payRest)
            assertEquals("", vm.state.value.principalInput)
            vm.submit()
            runCurrent()

            val saved = rig.entries.stored.single { it is Transaction.Repayment } as Transaction.Repayment
            assertEquals(Money.of("100", "USD"), saved.principal)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun loadingARepaymentEntryUsesTheRepaymentFormInEditMode() = runTest(work) {
        val debt = screenDebt(1, "Ali")
        val repayment = Transaction.Repayment(
            id = 7,
            side = DebtSide.I_OWE,
            accountId = 2,
            debtId = debt.id,
            accountAmount = Money.of("25", "USD"),
            principal = Money.of("25", "USD"),
            date = screenDay,
        )
        val rig = DebtScreenRig(
            initialDebts = listOf(debt),
            initialEntries = listOf(screenOpening(debt, amount = "100"), repayment),
            work = work,
            initialAccounts = listOf(account()),
        )
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = debt.id, repaymentId = repayment.id)
            runCurrent()

            assertEquals(repayment.id, vm.state.value.editingRepaymentId)
            assertEquals("25", vm.state.value.principalInput)
            assertEquals(account().id, vm.state.value.accountId)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun editingARepaymentCanImproveAnAlreadyNegativeDebt() = runTest(work) {
        val debt = screenDebt(1, "Ali")
        val repayment = Transaction.Repayment(
            id = 7,
            side = DebtSide.I_OWE,
            accountId = 2,
            debtId = debt.id,
            accountAmount = Money.of("100", "USD"),
            principal = Money.of("100", "USD"),
            date = screenDay,
        )
        val rig = DebtScreenRig(
            initialDebts = listOf(debt),
            initialEntries = listOf(screenOpening(debt, amount = "50"), repayment),
            work = work,
            initialAccounts = listOf(account()),
        )
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = debt.id, repaymentId = repayment.id)
            runCurrent()

            vm.setPrincipal("75")
            assertTrue(vm.state.value.canSubmit)
            vm.submit()
            runCurrent()

            val saved = rig.entries.stored.single { it is Transaction.Repayment } as Transaction.Repayment
            assertEquals(Money.of("75", "USD"), saved.principal)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun editingAPaidInFullRepaymentSubmitsTheChangedNoteAndDate() = runTest(work) {
        val debt = screenDebt(1, "Ali")
        val repayment = Transaction.Repayment(
            id = 7,
            side = DebtSide.I_OWE,
            accountId = 2,
            debtId = debt.id,
            accountAmount = Money.of("100", "USD"),
            principal = Money.of("100", "USD"),
            date = screenDay,
        )
        val rig = DebtScreenRig(
            initialDebts = listOf(debt),
            initialEntries = listOf(screenOpening(debt, amount = "100"), repayment),
            work = work,
            initialAccounts = listOf(account()),
        )
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = debt.id, repaymentId = repayment.id)
            runCurrent()

            vm.setNote("receipt")
            vm.setDate(screenDay.plusDays(1))
            assertTrue(vm.state.value.canSubmit)
            vm.submit()
            runCurrent()

            val saved = rig.entries.stored.single { it is Transaction.Repayment } as Transaction.Repayment
            assertEquals("receipt", saved.note)
            assertEquals(screenDay.plusDays(1), saved.date)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ---- W8 review fix round: behaviour changes, written RED first ----

    @Test fun twoTypedAmountsSaveTheTypedAccountAmountExactly() = runTest(work) {
        val rig = repaymentRig(account = account(currency = "SYP"), debtAmount = "1000", fx = fx(syp = unitsPerUsd(13000)))
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1)
            runCurrent()

            vm.setPrincipal("1000.00")
            vm.setAccountPrincipal("13000000.00")
            vm.submit()
            runCurrent()

            assertMoney("13000000.00", "SYP", onlyRepayment(rig).accountAmount)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun explicitCrossCurrencyWithInterestStampsTheTypedRateOnTheInterestLeg() = runTest(work) {
        val rig = repaymentRig(account = account(currency = "EUR"), categories = listOf(expenseCategory))
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1)
            runCurrent()

            vm.setPrincipal("100")
            vm.setAccountPrincipal("80")
            vm.setInterest("2")
            vm.selectInterestCategory(expenseCategory.id)
            vm.setRate("EUR", "1.25")
            vm.submit()
            runCurrent()

            assertEquals(0, BigDecimal("1.25").compareTo(onlyRepayment(rig).interestRateToBase))
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun editingACrossCurrencyRepaymentPreviewsTheStoredPairRateNotTodaysBook() = runTest(work) {
        val rig = sypEditRig(fx = fx(syp = unitsPerUsd(15000)))
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1, repaymentId = 7)
            runCurrent()

            val pair = checkNotNull(vm.state.value.pairRate)
            assertEquals("USD", pair.from)
            assertEquals("SYP", pair.to)
            assertEquals("13000", pair.rate)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun remainingDisplayShowsTheDebtsRemaining() = runTest(work) {
        val rig = repaymentRig()
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1)
            runCurrent()

            assertEquals("100.00 USD", vm.state.value.remainingDisplay)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun payingTheRestOfASyrianDebtShowsWhatTheDollarAccountMoves() = runTest(work) {
        val rig = repaymentRig(debtCode = "SYP", debtAmount = "1000050", fx = fx(syp = unitsPerUsd(13000)))
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1)
            runCurrent()

            vm.payTheRest(true)

            assertEquals("76.93 USD", vm.state.value.accountMovesDisplay)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun changingThePrincipalOnAnEditClearsTheUntouchedAccountAmountAndKeepsTheHistoricalRate() = runTest(work) {
        val rig = sypEditRig(fx = fx(syp = unitsPerUsd(15000)))
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1, repaymentId = 7)
            runCurrent()
            assertEquals("76.93", vm.state.value.accountPrincipalInput)

            vm.setPrincipal("500000")
            assertEquals("", vm.state.value.accountPrincipalInput)
            vm.submit()
            runCurrent()

            val saved = onlyRepayment(rig)
            assertMoney("38.46", "USD", saved.accountAmount)
            assertEquals(0, BigDecimal("13000").compareTo(saved.pairRate))
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun payTheRestCanBeTurnedOffAgain() = runTest(work) {
        val rig = repaymentRig()
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1)
            runCurrent()

            vm.payTheRest(true)
            assertTrue(vm.state.value.canSubmit)
            vm.payTheRest(false)

            assertFalse(vm.state.value.payRest)
            assertFalse(vm.state.value.canSubmit)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun anAccountAmountAloneWithInterestIsRefusedWithATypedMessage() = runTest(work) {
        val rig = repaymentRig(account = account(currency = "EUR"), categories = listOf(expenseCategory))
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1)
            runCurrent()

            vm.setAccountPrincipal("80")
            vm.setInterest("2")
            vm.selectInterestCategory(expenseCategory.id)
            vm.setRate("EUR", "1.25")
            vm.submit()
            runCurrent()

            assertEquals(UiMessage.AmountMissing, vm.state.value.error)
            assertTrue(rig.entries.stored.none { it is Transaction.Repayment })
            assertTrue(rig.errors.reported.isEmpty())
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun theInterestInputClearsWhenTheAccountCurrencyChanges() = runTest(work) {
        val euro = account(currency = "EUR").copy(id = 3, name = "Euro wallet")
        val rig = repaymentRig(accounts = listOf(account(), euro))
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1)
            runCurrent()

            vm.setInterest("5")
            vm.selectAccount(euro.id)

            assertEquals("", vm.state.value.interestInput)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun editingARepaymentKeepsItsInterestSubCategory() = runTest(work) {
        val sub = Category(6, "Bank fees", CategoryKind.EXPENSE, parentId = expenseCategory.id)
        val repayment = Transaction.Repayment(
            id = 7, side = DebtSide.I_OWE, accountId = 2, debtId = 1,
            accountAmount = Money.of("30", "USD"), principal = Money.of("25", "USD"),
            interest = Money.of("5", "USD"), interestCategoryId = expenseCategory.id,
            interestSubCategoryId = sub.id, date = screenDay,
        )
        val rig = repaymentRig(categories = listOf(expenseCategory, sub), extraEntries = listOf(repayment))
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1, repaymentId = 7)
            runCurrent()

            vm.setNote("receipt")
            vm.submit()
            runCurrent()

            assertEquals(sub.id, onlyRepayment(rig).interestSubCategoryId)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun anUnexpectedSubmitFailureIsReportedAndShownAsUnexpected() = runTest(work) {
        val rig = repaymentRig()
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1)
            runCurrent()
            val failure = IllegalStateException("disk gone")
            rig.entries.failWith = failure

            vm.setPrincipal("10")
            vm.submit()
            runCurrent()

            assertEquals(listOf<Throwable>(failure), rig.errors.reported)
            assertEquals(UiMessage.Unexpected, vm.state.value.error)
            assertFalse(vm.state.value.isSubmitting)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /**
     * The form's remaining is stale (100) when the rest is paid, because another repayment of 30 lands
     * first. Only a null principal lets the use case read the fresh 70; an explicit 100 would be refused.
     */
    @Test fun payTheRestSendsANullPrincipalSoTheUseCaseReadsTheFreshRemaining() = runTest(work) {
        val rig = repaymentRig()
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1)
            runCurrent()

            vm.payTheRest(true)
            rig.entries.insert(Transaction.Repayment(
                id = 50, side = DebtSide.I_OWE, accountId = 2, debtId = 1,
                accountAmount = Money.of("30", "USD"), principal = Money.of("30", "USD"), date = screenDay,
            ))
            vm.submit()
            runCurrent()

            val principals = rig.entries.stored.filterIsInstance<Transaction.Repayment>().map { it.principal }
            assertEquals(listOf(Money.of("30", "USD"), Money.of("70", "USD")), principals)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun aNoteOnlyEditOfAPaidInFullCrossCurrencyRepaymentKeepsTheDebtAtExactlyZero() = runTest(work) {
        val rig = sypEditRig(fx = fx(syp = unitsPerUsd(13000)), openingAmount = "1000050")
        val vm = rig.repayment().also { watch(it) }
        try {
            vm.load(debtId = 1, repaymentId = 7)
            runCurrent()

            vm.setNote("receipt")
            vm.submit()
            runCurrent()

            val saved = onlyRepayment(rig)
            assertMoney("1000050", "SYP", saved.principal)
            assertMoney("76.93", "USD", saved.accountAmount)
            assertEquals("receipt", saved.note)
            assertTrue(checkNotNull(vm.state.value.debt).summary.remaining.isZero)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private val expenseCategory = Category(5, "Loan interest", CategoryKind.EXPENSE)
    private val sypCurrency = Currency("SYP", "£S", 2)

    private fun fx(syp: BigDecimal? = null) = FxSnapshot(
        "USD",
        if (syp == null) emptyMap() else mapOf("SYP" to syp),
        listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), sypCurrency),
    )

    /** The book rate (USD per 1 SYP) for a book that prices [syp] SYP to one dollar. */
    private fun unitsPerUsd(syp: Int): BigDecimal = BigDecimal.ONE.divide(BigDecimal(syp), 20, RoundingMode.HALF_EVEN)

    private fun assertMoney(amount: String, code: String, actual: Money) {
        assertEquals(code, actual.currencyCode)
        assertEquals(0, BigDecimal(amount).compareTo(actual.amount))
    }

    private fun onlyRepayment(rig: DebtScreenRig) =
        rig.entries.stored.filterIsInstance<Transaction.Repayment>().single()

    /** A SYP debt of 2,000,000 (or [openingAmount]) with SYP 1,000,050 repaid from a USD account at 13,000. */
    private fun sypEditRig(fx: FxSnapshot, openingAmount: String = "2000000"): DebtScreenRig {
        val debt = screenDebt(1, "Ali", code = "SYP")
        val repayment = Transaction.Repayment(
            id = 7, side = DebtSide.I_OWE, accountId = 2, debtId = debt.id,
            accountAmount = Money.of("76.93", "USD"), principal = Money.of("1000050", "SYP"),
            pairRate = BigDecimal("13000"), date = screenDay,
        )
        return DebtScreenRig(
            initialDebts = listOf(debt),
            initialEntries = listOf(screenOpening(debt, amount = openingAmount), repayment),
            work = work,
            initialAccounts = listOf(account()),
        ).also { it.fxState.value = fx }
    }

    private fun repaymentRig(
        account: Account = account(),
        categories: List<Category> = emptyList(),
        roles: Map<CategoryRole, Long> = emptyMap(),
        accounts: List<Account> = listOf(account),
        debtCode: String = "USD",
        debtAmount: String = "100",
        fx: FxSnapshot? = null,
        extraEntries: List<Transaction> = emptyList(),
    ): DebtScreenRig {
        val debt = screenDebt(1, "Ali", code = debtCode)
        return DebtScreenRig(
            initialDebts = listOf(debt),
            initialEntries = listOf(screenOpening(debt, amount = debtAmount)) + extraEntries,
            work = work,
            initialAccounts = accounts,
            initialCategories = categories,
            roleBindings = roles,
        ).also { rig -> fx?.let { rig.fxState.value = it } }
    }

    private fun account(currency: String = "USD") = Account(
        id = 2,
        name = "Wallet",
        typeKey = "cash",
        currencyCode = currency,
        openingBalance = Money.zero(currency),
    )

    private fun TestScope.watch(viewModel: RecordRepaymentViewModel): RecordRepaymentViewModel {
        backgroundScope.launch { viewModel.state.collect {} }
        return viewModel
    }

    private data class RepaymentRoleCase(
        val name: String,
        val categories: List<Category>,
        val roles: Map<CategoryRole, Long>,
        val expectedCategoryId: Long?,
    )
}
