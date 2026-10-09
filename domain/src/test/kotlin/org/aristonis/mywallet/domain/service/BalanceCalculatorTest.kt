package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountLeg
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Assumed signature: `BalanceCalculator.balances(accounts: List<Account>, legs: List<AccountLeg>):
 * List<AccountBalance>`, where the balance is the opening balance plus the sum of that account's legs.
 * The per-transaction `apply` function is gone.
 */
class BalanceCalculatorTest {

    private val today = LocalDate.of(2026, 7, 2)
    private fun acct(id: Long, currency: String = "USD", opening: String = "0") =
        Account(id = id, name = "a$id", typeKey = "cash", currencyCode = currency, openingBalance = Money.of(opening, currency))

    private fun usd(a: String) = Money.of(a, "USD")

    /** The account legs a stored entry holds, as the balance read projects them. */
    private fun legsOf(vararg entries: Transaction): List<AccountLeg> = entries.flatMap { entry ->
        entry.toParts().legs.mapNotNull { leg ->
            (leg.target as? LegTarget.Account)?.let { AccountLeg(it.id, leg.amount) }
        }
    }

    @Test
    fun openingBalanceOnly() {
        val result = BalanceCalculator.balances(listOf(acct(1, opening = "100")), emptyList())
        assertEquals(usd("100"), result.single().balance)
    }

    @Test
    fun balanceIsTheOpeningBalancePlusTheSumOfItsLegs() {
        val legs = listOf(AccountLeg(1, usd("50")), AccountLeg(1, usd("-20")), AccountLeg(1, usd("0.25")))
        val result = BalanceCalculator.balances(listOf(acct(1, opening = "100")), legs)
        assertEquals(usd("130.25"), result.single().balance)
    }

    @Test
    fun incomeAddsAndExpenseSubtractsThroughTheirAccountLegs() {
        val legs = legsOf(
            Transaction.Income(id = 1, accountId = 1, amount = usd("50"), categoryId = 1, date = today),
            Transaction.Expense(id = 2, accountId = 1, amount = usd("20"), categoryId = 2, date = today),
        )
        val result = BalanceCalculator.balances(listOf(acct(1, opening = "100")), legs)
        assertEquals(usd("130"), result.single().balance)
    }

    @Test
    fun aTransferMovesMoneyBetweenAccounts() {
        val legs = legsOf(
            Transaction.Transfer(
                id = 1, sourceAccountId = 1, destAccountId = 2,
                sourceAmount = usd("30"), destAmount = usd("30"), rateUsed = BigDecimal.ONE, date = today,
            ),
        )
        val byId = BalanceCalculator.balances(listOf(acct(1, opening = "100"), acct(2)), legs)
            .associateBy { it.account.id }
        assertEquals(usd("70"), byId.getValue(1).balance)
        assertEquals(usd("30"), byId.getValue(2).balance)
    }

    @Test
    fun ignoresLegsOfOtherAccounts() {
        val result = BalanceCalculator.balances(listOf(acct(1, opening = "100")), listOf(AccountLeg(2, usd("999"))))
        assertEquals(usd("100"), result.single().balance)
    }

    @Test
    fun keepsOneBalancePerAccountInTheOrderGiven() {
        val result = BalanceCalculator.balances(
            listOf(acct(2, opening = "5"), acct(1, opening = "7")),
            listOf(AccountLeg(1, usd("1")), AccountLeg(2, usd("2"))),
        )
        assertEquals(listOf(2L, 1L), result.map { it.account.id })
        assertEquals(listOf(usd("7"), usd("8")), result.map { it.balance })
    }

    @Test
    fun anAccountMayGoNegative() {
        val result = BalanceCalculator.balances(listOf(acct(1, opening = "10")), listOf(AccountLeg(1, usd("-25.5"))))
        assertEquals(usd("-15.5"), result.single().balance)
    }
}
