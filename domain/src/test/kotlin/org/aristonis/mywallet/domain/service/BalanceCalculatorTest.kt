package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class BalanceCalculatorTest {

    private val today = LocalDate.of(2026, 7, 2)
    private fun acct(id: Long, currency: String = "USD", opening: String = "0") =
        Account(id = id, name = "a$id", typeKey = "cash", currencyCode = currency, openingBalance = Money.of(opening, currency))

    @Test
    fun openingBalanceOnly() {
        val result = BalanceCalculator.balances(listOf(acct(1, opening = "100")), emptyList())
        assertEquals(Money.of("100", "USD"), result.single().balance)
    }

    @Test
    fun incomeAdds_expenseSubtracts() {
        val txs = listOf(
            Transaction.Income(id = 1, accountId = 1, amount = Money.of("50", "USD"), categoryId = 1, date = today),
            Transaction.Expense(id = 2, accountId = 1, amount = Money.of("20", "USD"), categoryId = 2, date = today),
        )
        val result = BalanceCalculator.balances(listOf(acct(1, opening = "100")), txs)
        assertEquals(Money.of("130", "USD"), result.single().balance)
    }

    @Test
    fun transfer_movesBetweenAccounts() {
        val txs = listOf(
            Transaction.Transfer(
                id = 1, sourceAccountId = 1, destAccountId = 2,
                sourceAmount = Money.of("30", "USD"), destAmount = Money.of("30", "USD"),
                rateUsed = BigDecimal.ONE, date = today,
            ),
        )
        val byId = BalanceCalculator.balances(listOf(acct(1, opening = "100"), acct(2, opening = "0")), txs)
            .associateBy { it.account.id }
        assertEquals(Money.of("70", "USD"), byId.getValue(1).balance)
        assertEquals(Money.of("30", "USD"), byId.getValue(2).balance)
    }

    @Test
    fun ignoresUnrelatedAccountTransactions() {
        val txs = listOf(Transaction.Income(id = 1, accountId = 2, amount = Money.of("999", "USD"), categoryId = 1, date = today))
        val result = BalanceCalculator.balances(listOf(acct(1, opening = "100")), txs)
        assertEquals(Money.of("100", "USD"), result.single().balance)
    }
}
