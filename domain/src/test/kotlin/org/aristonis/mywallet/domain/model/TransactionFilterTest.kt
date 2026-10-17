package org.aristonis.mywallet.domain.model

import org.aristonis.mywallet.domain.ledger.EntryRecipes
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.service.TransactionFilterMatch
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class TransactionFilterTest {
    private val date = LocalDate.of(2026, 10, 17)
    private fun usd(value: String) = Money.of(value, "USD")
    private val salary = Transaction.Income(
        accountId = 1, amount = usd("1000"), categoryId = 10, date = date,
        fee = usd("5"), feeCategoryId = 40,
        deductions = listOf(DeductionLine(
            itemId = 1, position = 0, name = "Tax", mode = DeductionMode.PERCENT,
            value = BigDecimal.TEN, valueCurrencyCode = null, timing = DeductionTiming.LATER,
            categoryId = 20, subCategoryId = 21, amount = usd("100"), debtId = 2,
        )),
    )

    @Test fun fakeRepositoryFiltersBeforeTakingPageLimit() = kotlinx.coroutines.test.runTest {
        val entries = (1L..6L).map { id ->
            if (id % 2L == 0L) salary.copy(id = id) else Transaction.Expense(
                id = id, accountId = 1, amount = usd("10"), categoryId = 30, date = date,
            )
        }
        val repository = org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository(entries)
        val filter = TransactionFilter(types = setOf(TypeGroup.INCOME))
        val first = repository.observePage(DateRange.ALL_TIME, null, 2, filter).first()
        assertEquals(listOf(6L, 4L), first.map { it.id })
        val after = org.aristonis.mywallet.domain.port.EntryKey(date, 4)
        assertEquals(listOf(2L), repository.observePage(DateRange.ALL_TIME, after, 2, filter).first().map { it.id })
    }

    @Test fun defaultsAndSheetCount() {
        assertEquals(emptySet<TypeGroup>(), TransactionFilter().types)
        assertEquals(0, TransactionFilter(types = setOf(TypeGroup.INCOME)).activeSheetCount)
        assertEquals(1, TransactionFilter(accountIds = setOf(1)).activeSheetCount)
        assertEquals(1, TransactionFilter(categoryIds = setOf(2)).activeSheetCount)
        assertEquals(2, TransactionFilter(accountIds = setOf(1), categoryIds = setOf(2)).activeSheetCount)
    }

    @Test fun everyRecipeExampleBelongsToExactlyOneChipAndAll() {
        val expected = mapOf(
            "INCOME" to TypeGroup.INCOME, "EARNED_UNPAID" to TypeGroup.INCOME,
            "EXPENSE" to TypeGroup.EXPENSE, "REFUND" to TypeGroup.EXPENSE,
            "CREDIT_PURCHASE" to TypeGroup.EXPENSE, "TRANSFER" to TypeGroup.TRANSFER,
            "BORROW" to TypeGroup.DEBTS, "LEND" to TypeGroup.DEBTS,
            "OWED_BEFORE" to TypeGroup.DEBTS, "REPAYMENT" to TypeGroup.DEBTS,
        )
        EntryRecipes.all.flatMap { it.examples() }.forEach { entry ->
            val parts = entry.toParts()
            val group = if (parts.header.kind == "WRITE_OFF") {
                if (parts.legs.any { it.role == LegRole.LOSS }) TypeGroup.EXPENSE else TypeGroup.INCOME
            } else expected.getValue(parts.header.kind)
            assertEquals(group, TransactionFilterMatch.typeGroup(entry))
            assertEquals(group, TransactionFilterMatch.typeGroup(parts))
            assertTrue(TransactionFilterMatch.matches(entry, TransactionFilter()))
            assertEquals(listOf(group), TypeGroup.entries.filter { it != TypeGroup.ALL }.filter {
                TransactionFilterMatch.matches(entry, TransactionFilter(types = setOf(it)))
            })
        }
    }

    @Test fun owedIncomeAndPaidLaterExpenseKeepTheirEntryType() {
        val expense = Transaction.Expense(accountId = 1, amount = usd("1000"), categoryId = 30,
            date = date, owed = usd("700"), owedDebtId = 3)
        assertTrue(TransactionFilterMatch.matches(salary, TransactionFilter(types = setOf(TypeGroup.INCOME))))
        assertFalse(TransactionFilterMatch.matches(salary, TransactionFilter(types = setOf(TypeGroup.DEBTS))))
        assertTrue(TransactionFilterMatch.matches(expense, TransactionFilter(types = setOf(TypeGroup.EXPENSE))))
        assertFalse(TransactionFilterMatch.matches(expense, TransactionFilter(types = setOf(TypeGroup.DEBTS))))
    }

    @Test fun parentAndChildCategoryMatchDeductionAndReturnItsExactLeg() {
        for (category in listOf(20L, 21L)) {
            assertTrue(TransactionFilterMatch.matches(salary, TransactionFilter(categoryIds = setOf(category))))
            val matches = TransactionFilterMatch.matchedCategoryLegs(salary, setOf(category))
            assertEquals(listOf(LegRole.DEDUCTION), matches.map { it.role })
            assertEquals(listOf(usd("100")), matches.map { it.amount })
        }
        assertEquals(listOf(LegRole.FEE), TransactionFilterMatch.matchedCategoryLegs(salary, setOf(40)).map { it.role })
        assertTrue(TransactionFilterMatch.matches(salary, TransactionFilter(categoryIds = setOf(40))))
        assertTrue(TransactionFilterMatch.matches(salary, TransactionFilter(categoryIds = setOf(10))))
        assertFalse(TransactionFilterMatch.matches(salary, TransactionFilter(categoryIds = setOf(99))))
    }

    @Test fun sheetAndTypeTermsCombineWithAnd() {
        assertTrue(TransactionFilterMatch.matches(salary,
            TransactionFilter(types = setOf(TypeGroup.INCOME), accountIds = setOf(1), categoryIds = setOf(20))))
        assertFalse(TransactionFilterMatch.matches(salary,
            TransactionFilter(types = setOf(TypeGroup.EXPENSE), accountIds = setOf(1), categoryIds = setOf(20))))
        assertFalse(TransactionFilterMatch.matches(salary, TransactionFilter(accountIds = setOf(9), categoryIds = setOf(20))))
        assertFalse(TransactionFilterMatch.matches(salary, TransactionFilter(accountIds = setOf(1), categoryIds = setOf(9))))
    }

    @Test fun eitherTransferAccountMatchesAndDebtOnlyEntryHasNoAccount() {
        val transfer = EntryRecipes.forKind("TRANSFER")!!.examples().first()
        val accounts = transfer.toParts().legs.mapNotNull {
            (it.target as? org.aristonis.mywallet.domain.ledger.LegTarget.Account)?.id
        }
        accounts.forEach { assertTrue(TransactionFilterMatch.matches(transfer, TransactionFilter(accountIds = setOf(it)))) }
        val owedBefore = EntryRecipes.forKind("OWED_BEFORE")!!.examples().first()
        assertFalse(TransactionFilterMatch.matches(owedBefore, TransactionFilter(accountIds = setOf(1))))
    }
    @Test fun selectionsUseOrWithinGroupsAndAndAcrossGroups() {
        val filter = TransactionFilter(
            types = setOf(TypeGroup.INCOME, TypeGroup.EXPENSE),
            accountIds = setOf(1L, 99L), categoryIds = setOf(20L, 88L),
        )
        assertTrue(TransactionFilterMatch.matches(salary, filter))
        assertFalse(TransactionFilterMatch.matches(salary, filter.copy(types = setOf(TypeGroup.DEBTS, TypeGroup.TRANSFER))))
        assertFalse(TransactionFilterMatch.matches(salary, filter.copy(accountIds = setOf(8L, 9L))))
        assertFalse(TransactionFilterMatch.matches(salary, filter.copy(categoryIds = setOf(8L, 9L))))
        assertEquals(2, filter.activeSheetCount)
        assertEquals(1, filter.copy(categoryIds = emptySet()).activeSheetCount)
    }

    @Test fun overlappingParentAndChildSelectionsReturnEachLegOnce() {
        val legs = TransactionFilterMatch.matchedCategoryLegs(salary, setOf(20L, 21L, 40L))
        assertEquals(listOf(LegRole.DEDUCTION, LegRole.FEE).toSet(), legs.map { it.role }.toSet())
        assertEquals(2, legs.size)
        assertTrue(TransactionFilterMatch.matchedCategoryLegs(salary, emptySet()).isEmpty())
    }

    @Test fun selectingIncomeAndExpenseIncludesBothWriteOffRoles() {
        val filter = TransactionFilter(types = setOf(TypeGroup.INCOME, TypeGroup.EXPENSE))
        EntryRecipes.forKind("WRITE_OFF")!!.examples().forEach {
            assertTrue(TransactionFilterMatch.matches(it, filter))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun allIsRepresentedByEmptySelectionRatherThanAnAllMember() {
        TransactionFilter(types = setOf(TypeGroup.ALL))
    }

}
