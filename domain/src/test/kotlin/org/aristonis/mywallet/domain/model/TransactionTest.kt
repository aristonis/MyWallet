package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class TransactionTest {

    private val today = LocalDate.of(2026, 7, 2)
    private fun usd(a: String) = Money.of(a, "USD")

    @Test
    fun incomeStoresPositiveAmount() {
        val t = Transaction.Income(accountId = 1, amount = usd("10"), categoryId = 2, date = today)
        assertEquals(usd("10"), t.amount)
    }

    @Test
    fun incomeFeeIsAbsentByDefault() {
        val income = Transaction.Income(accountId = 1, amount = usd("1000"), categoryId = 2, date = today)

        assertNull(income.fee)
        assertNull(income.feeCategoryId)
    }

    @Test
    fun incomeStoresItsFeeAndCategoryWithoutRounding() {
        val fee = usd("20.123456789")

        val income = Transaction.Income(
            accountId = 1, amount = usd("1000"), categoryId = 2, date = today, fee = fee, feeCategoryId = 70,
        )

        assertEquals(fee, income.fee)
        assertEquals(70L, income.feeCategoryId)
        assertEquals(usd("1000"), income.amount)
    }

    @Test
    fun incomeFeeInAnotherCurrencyIsRefusedByTheModel() {
        val fee = Money.of("20", "SYP")

        val error = assertThrows(IllegalArgumentException::class.java) {
            Transaction.Income(accountId = 1, amount = usd("1000"), categoryId = 2, date = today, fee = fee)
        }

        assertEquals("fee currency must match USD", error.message)
    }

    @Test
    fun incomeDraftFeeNeedsNoCategoryAndItsSignIsLeftToTheSave() {
        val fee = usd("-1")

        val income = Transaction.Income(
            accountId = 1, amount = usd("1000"), categoryId = 2, date = today, fee = fee,
        )

        assertEquals(fee, income.fee)
        assertNull(income.feeCategoryId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun income_nonPositive_throws() {
        Transaction.Income(accountId = 1, amount = usd("0"), categoryId = 2, date = today)
    }

    @Test(expected = IllegalArgumentException::class)
    fun transfer_sameAccount_throws() {
        Transaction.Transfer(
            sourceAccountId = 1,
            destAccountId = 1,
            sourceAmount = usd("5"),
            destAmount = usd("5"),
            rateUsed = BigDecimal.ONE,
            date = today,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun transfer_zeroRate_throws() {
        Transaction.Transfer(
            sourceAccountId = 1,
            destAccountId = 2,
            sourceAmount = usd("5"),
            destAmount = usd("5"),
            rateUsed = BigDecimal.ZERO,
            date = today,
        )
    }

    @Test
    fun transferHoldsBothAmounts() {
        val t = Transaction.Transfer(
            sourceAccountId = 1,
            destAccountId = 2,
            sourceAmount = usd("5"),
            destAmount = Money.of("4.6", "EUR"),
            rateUsed = BigDecimal("0.92"),
            date = today,
        )
        assertEquals(usd("5"), t.sourceAmount)
        assertEquals(Money.of("4.6", "EUR"), t.destAmount)
    }

    @Test
    fun expenseFeeInAnotherCurrencyIsRefusedByTheModel() {
        assertThrows(IllegalArgumentException::class.java) {
            Transaction.Expense(
                accountId = 1, amount = usd("10"), categoryId = 2, date = today, fee = Money.of("1", "SYP"),
            )
        }
    }

    @Test
    fun expenseOwedIsAbsentByDefault() {
        val expense = Transaction.Expense(accountId = 1, amount = usd("1000"), categoryId = 2, date = today)

        assertNull(expense.owed)
        assertNull(expense.owedDebtId)
    }

    @Test
    fun expenseStoresItsPartialOwedAmountAndDebtWithoutRounding() {
        val amount = usd("1000.123456789")
        val owed = usd("700.123456789")

        val expense = Transaction.Expense(
            accountId = 1, amount = amount, categoryId = 2, date = today,
            fee = usd("3"), feeCategoryId = 70, owed = owed, owedDebtId = 9,
        )

        assertEquals(amount, expense.amount)
        assertEquals(owed, expense.owed)
        assertEquals(9L, expense.owedDebtId)
        assertEquals(usd("300"), expense.amount - requireNotNull(expense.owed))
    }

    @Test
    fun expenseAllowsTheWholeAmountToBeOwedWithoutResolvingADebt() {
        val amount = usd("1000.00")
        val owed = usd("1000.000000000")

        val expense = Transaction.Expense(
            accountId = 1, amount = amount, categoryId = 2, date = today, owed = owed,
        )

        assertEquals(owed, expense.owed)
        assertNull(expense.owedDebtId)
        assertEquals(Money.zero("USD"), expense.amount - requireNotNull(expense.owed))
    }

    @Test
    fun expenseOwedInAnotherCurrencyIsRefusedByTheModel() {
        val owed = Money.of("700", "SYP")

        val error = assertThrows(IllegalArgumentException::class.java) {
            Transaction.Expense(accountId = 1, amount = usd("1000"), categoryId = 2, date = today, owed = owed)
        }

        assertEquals("owed currency must match USD", error.message)
    }

    @Test
    fun expenseZeroOwedIsRefusedByTheModel() {
        val owed = Money.zero("USD")

        val error = assertThrows(IllegalArgumentException::class.java) {
            Transaction.Expense(accountId = 1, amount = usd("1000"), categoryId = 2, date = today, owed = owed)
        }

        assertEquals("expense owed must be > 0", error.message)
    }

    @Test
    fun expenseNegativeOwedIsRefusedByTheModel() {
        val owed = usd("-0.000000001")

        val error = assertThrows(IllegalArgumentException::class.java) {
            Transaction.Expense(accountId = 1, amount = usd("1000"), categoryId = 2, date = today, owed = owed)
        }

        assertEquals("expense owed must be > 0", error.message)
    }

    @Test
    fun expenseOwedAboveTheAmountIsRefusedWithoutRounding() {
        val owed = usd("1000.000000001")

        val error = assertThrows(IllegalArgumentException::class.java) {
            Transaction.Expense(accountId = 1, amount = usd("1000"), categoryId = 2, date = today, owed = owed)
        }

        assertEquals("expense owed must not exceed amount", error.message)
    }

    @Test
    fun transferFeeInAnotherCurrencyIsRefusedByTheModel() {
        assertThrows(IllegalArgumentException::class.java) {
            Transaction.Transfer(
                sourceAccountId = 1, destAccountId = 2, sourceAmount = usd("10"), destAmount = usd("10"),
                rateUsed = BigDecimal.ONE, date = today, fee = Money.of("1", "SYP"),
            )
        }
    }

    /** A draft carries its fee without a category (save resolves it) and a negative fee gets a typed save error. */
    @Test
    fun aDraftFeeNeedsNoCategoryAndItsSignIsLeftToTheSave() {
        val expense = Transaction.Expense(
            accountId = 1, amount = usd("10"), categoryId = 2, date = today, fee = usd("-1"),
        )
        val transfer = Transaction.Transfer(
            sourceAccountId = 1, destAccountId = 2, sourceAmount = usd("10"), destAmount = usd("10"),
            rateUsed = BigDecimal.ONE, date = today, fee = usd("12"),
        )
        assertEquals(usd("-1"), expense.fee)
        assertEquals(usd("12"), transfer.fee)
    }
}
