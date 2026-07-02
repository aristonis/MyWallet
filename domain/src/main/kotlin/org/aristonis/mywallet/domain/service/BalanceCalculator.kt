package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountBalance
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction

/**
 * Pure balance math: `balance = openingBalance + Σ signed transaction effects on that account`
 * (D11: opening balance is a field, not a transaction). No I/O — trivially unit-testable.
 *
 * Note: O(accounts × transactions). The Room adapter (SG-4) does this in SQL for performance; this
 * reference implementation keeps the rule readable and testable.
 */
object BalanceCalculator {

    fun balances(accounts: List<Account>, transactions: List<Transaction>): List<AccountBalance> =
        accounts.map { account ->
            val balance = transactions.fold(account.openingBalance) { running, tx ->
                apply(running, account.id, tx)
            }
            AccountBalance(account, balance)
        }

    /** Applies one transaction's effect to [accountId]'s running [balance] (unchanged if unrelated). */
    fun apply(balance: Money, accountId: Long, transaction: Transaction): Money =
        when (transaction) { // exhaustive over the sealed Transaction — no `else` needed
            is Transaction.Income ->
                if (transaction.accountId == accountId) balance + transaction.amount else balance

            is Transaction.Expense ->
                if (transaction.accountId == accountId) balance - transaction.amount else balance

            is Transaction.Transfer -> when (accountId) {
                transaction.sourceAccountId -> balance - transaction.sourceAmount
                transaction.destAccountId -> balance + transaction.destAmount
                else -> balance
            }
        }
}
