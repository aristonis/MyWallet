package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountBalance
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.AccountLeg

/**
 * Pure balance math: `balance = openingBalance + sum of that account's legs`. The opening balance is
 * a field on the account, not an entry. Every leg already carries its sign, so money out of an
 * account is a negative leg and nothing here needs to know which kind of entry wrote it.
 */
object BalanceCalculator {

    /**
     * One balance per account in [accounts], in the order given. Legs are grouped once, so the cost
     * is linear in accounts plus legs. A leg on an account not in [accounts] is ignored. A leg in
     * another currency than its account fails loudly in [Money.plus] rather than being summed.
     */
    fun balances(accounts: List<Account>, legs: List<AccountLeg>): List<AccountBalance> {
        val legsByAccount = legs.groupBy { it.accountId }
        return accounts.map { account ->
            val balance = legsByAccount[account.id].orEmpty()
                .fold(account.openingBalance) { running: Money, leg -> running + leg.amount }
            AccountBalance(account, balance)
        }
    }
}
