package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.HomeSummary
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.InjectedFault
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.aristonis.mywallet.domain.usecase.fake.deductionItem
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SalaryAcceptanceTest {
    @Test fun salaryKeepsGrossIncomeAndTracksAllDeductionsWhileOnlyPaidNowReducesBank() = runTest {
        val rig = salaryRig()
        val saved = rig.saveEntry(salary(), emptyMap())
        val stored = rig.entries.findById(saved.id) as Transaction.Income
        assertEquals(usd("980"), GetAccountBalances(rig.accountRepository,
            rig.ledger)().first().single { it.account.id == 1L }.balance)
        val summary = tracking(rig)
        assertEquals(usd("1000"), summary.income)
        assertEquals(usd("170"), summary.expense)
        assertEquals(usd("830"), summary.net)
        val balances = GetAccountBalances(rig.accountRepository, rig.ledger)()
        val home = ComputeHomeSummary(rig.ledger, fakeFx(EntryWorld.currencies,
            listOf(ExchangeRate("SYP", BigDecimal("0.0001"))), "USD"))(balances).first() as HomeSummary.Amounts
        assertEquals(usd("980"), home.pocket)
        assertEquals(usd("150"), home.iOwe)
        assertEquals(usd("830"), home.balance)
        assertEquals(2, rig.debts.stored.size)
        assertEquals(listOf("Tax", "Family", "Bills"), stored.deductions.map { it.name })
        assertNull(stored.deductions.last().debtId)
    }

    @Test fun repeatedSalariesAggregateTaxAndRepaymentAddsNoExpense() = runTest {
        val rig = salaryRig()
        repeat(2) { rig.saveEntry(salary(), emptyMap()) }
        val tax = rig.debts.stored.single { it.counterparty == "Tax" }
        assertEquals(usd("200"), remaining(rig, tax.id))
        val expenseBefore = tracking(rig).expense
        rig.saveEntry(payment(tax.id, "200"), emptyMap())
        assertEquals(usd("0"), remaining(rig, tax.id))
        assertEquals(expenseBefore, tracking(rig).expense)
        assertEquals(2, rig.debts.stored.size)
    }

    @Test fun taxCanBePaidInTwoPartsFromTwoAccounts() = runTest {
        val rig = salaryRig()
        rig.saveEntry(salary(), emptyMap())
        val tax = rig.debts.stored.single { it.counterparty == "Tax" }
        rig.saveEntry(payment(tax.id, "40"), emptyMap())
        assertEquals(usd("60"), remaining(rig, tax.id))
        rig.saveEntry(payment(tax.id, "60", accountId = 3), emptyMap())
        assertEquals(usd("0"), remaining(rig, tax.id))
        val balances = GetAccountBalances(rig.accountRepository, rig.ledger)().first()
        assertEquals(usd("940"), balances.single { it.account.id == 1L }.balance)
        assertEquals(usd("-60"), balances.single { it.account.id == 3L }.balance)
        assertEquals(usd("170"), tracking(rig).expense)
    }

    @Test fun reducingTaxAfterRepaymentRefusesTheWholeSalaryEdit() = runTest {
        val rig = salaryRig()
        val saved = rig.saveEntry(salary(), emptyMap())
        val original = rig.entries.findById(saved.id) as Transaction.Income
        val tax = rig.debts.stored.single { it.counterparty == "Tax" }
        rig.saveEntry(payment(tax.id, "100"), emptyMap())
        val before = rig.entries.rows.value
        val reduced = original.copy(amount = usd("500"), deductions = original.deductions.map {
            it.copy(amount = Money.of(it.amount.amount / BigDecimal("2"), it.amount.currencyCode),
                value = it.value / BigDecimal("2"))
        })
        val failure = runCatching { rig.saveEntry(reduced, emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(before, rig.entries.rows.value)
        assertEquals(usd("0"), remaining(rig, tax.id))
    }

    @Test fun sameTaxNameInAnotherCurrencyCreatesASeparateDebt() = runTest {
        val rig = salaryRig()
        rig.saveEntry(salary(), emptyMap())
        rig.saveEntry(salary("SYP", 2), emptyMap())
        val taxes = rig.debts.stored.filter { it.counterparty == "Tax" }
        assertEquals(setOf("USD", "SYP"), taxes.map { it.currencyCode }.toSet())
        taxes.forEach { assertEquals(Money.of("100", it.currencyCode), remaining(rig, it.id)) }
    }

    @Test fun deletingAndUndoingSalaryRestoresSnapshotsBalancesAndDebtsExactly() = runTest {
        val rig = salaryRig()
        val id = rig.saveEntry(salary(), emptyMap()).id
        val before = rig.entries.rows.value
        val balances = GetAccountBalances(rig.accountRepository, rig.ledger)().first()
        val debts = rig.debts.stored
        val bundle = rig.deleteEntry(id)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertTrue(rig.ledger.debtLegs(debts.map { it.id }.toSet()).isEmpty())
        rig.restoreEntries(bundle)
        assertEquals(before, rig.entries.rows.value)
        assertEquals(balances, GetAccountBalances(rig.accountRepository, rig.ledger)().first())
        assertEquals(debts, rig.debts.stored)
    }

    @Test fun aFaultAfterSalaryWriteRollsBackResolvedDebtsAndEveryDeduction() = runTest {
        val rig = salaryRig(faultAfterWrite = true)
        val failure = runCatching { rig.saveEntry(salary(), emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is InjectedFault)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertTrue(rig.debts.stored.isEmpty())
    }

    @Test fun deletingARepaidSalaryRefusesWithoutLosingItsSnapshots() = runTest {
        val rig = salaryRig()
        val id = rig.saveEntry(salary(), emptyMap()).id
        val tax = rig.debts.stored.single { it.counterparty == "Tax" }
        rig.saveEntry(payment(tax.id, "100"), emptyMap())
        val before = rig.entries.rows.value
        val failure = runCatching { rig.deleteEntry(id) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(before, rig.entries.rows.value)
        assertEquals(usd("0"), remaining(rig, tax.id))
    }

    private suspend fun remaining(rig: SaveEntryRig, id: Long) =
        DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(id))).getValue(id).remaining

    private suspend fun tracking(rig: SaveEntryRig) =
        (ComputeCategoryBreakdown(rig.ledger, fakeFx(EntryWorld.currencies, emptyList(), "USD"))(
            DateRange.ALL_TIME,
        ).first() as CategoryBreakdownResult.Resolved).breakdown.summary
}

internal fun usd(value: String) = Money.of(value, "USD")

internal fun salaryRig(
    faultAfterWrite: Boolean = false,
    savedRates: Map<String, String> = emptyMap(),
    accounts: List<Account> = listOf(EntryWorld.usdAccount, EntryWorld.sypAccount, EntryWorld.secondUsdAccount),
) = SaveEntryRig(
    accounts = accounts,
    categories = listOf(EntryWorld.incomeCategory, EntryWorld.expenseCategory) + listOf(
        Category(id = 20, name = "Tax", kind = CategoryKind.EXPENSE),
        Category(id = 21, name = "Family", kind = CategoryKind.EXPENSE),
        Category(id = 22, name = "Bills", kind = CategoryKind.EXPENSE),
    ),
    initialDeductionItems = listOf(
        deductionItem(id = 10, name = "Tax", expenseCategoryId = 20),
        deductionItem(id = 11, name = "Family", value = "5", expenseCategoryId = 21),
        deductionItem(id = 12, name = "Bills", value = "2", timing = DeductionTiming.NOW, expenseCategoryId = 22),
    ),
    atomic = true, faultAfterWrite = faultAfterWrite, savedRates = savedRates,
)

internal fun salary(currency: String = "USD", accountId: Long = 1) =
    EntryWorld.income(accountId = accountId, amount = "1000", currency = currency).copy(
        deductions = listOf(
            salaryLine(10, 0, "Tax", 20, "100", currency),
            salaryLine(11, 1, "Family", 21, "50", currency),
            salaryLine(12, 2, "Bills", 22, "20", currency, DeductionTiming.NOW),
        ),
    )

internal fun salaryLine(itemId: Long, position: Int, name: String, categoryId: Long, amount: String,
    currency: String = "USD", timing: DeductionTiming = DeductionTiming.LATER) = DeductionLine(
    itemId = itemId, position = position, name = name, mode = DeductionMode.FIXED,
    value = BigDecimal(amount), valueCurrencyCode = currency, timing = timing,
    categoryId = categoryId, amount = Money.of(amount, currency),
)

internal fun payment(debtId: Long, amount: String, accountId: Long = 1) = Transaction.Repayment(
    side = DebtSide.I_OWE, accountId = accountId, debtId = debtId, accountAmount = usd(amount),
    principal = usd(amount), date = EntryWorld.day,
)
