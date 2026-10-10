package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.AccountBalance
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.domain.model.CreditStatus
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.service.BalanceCalculator

/**
 * Live per-account balances: each account's opening balance plus its account legs. Only the leg
 * projection is read, never whole entries. `combine` re-runs the sum whenever accounts or legs
 * change, so a screen collecting this updates itself.
 *
 * An account whose type is registered in [types] as owed when negative (a credit card) also gets its
 * [CreditStatus]. Any other account, including one whose type key is not registered, gets none: its
 * balance is an asset and a negative one is never read as debt.
 */
class GetAccountBalances(
    private val accounts: AccountRepository,
    private val ledger: LedgerQueries,
    private val types: AccountTypeRegistry = AccountTypeRegistry(),
) {
    operator fun invoke(): Flow<List<AccountBalance>> =
        combine(accounts.observeAll(), ledger.observeAccountLegs()) { accountList, legs ->
            BalanceCalculator.balances(accountList, legs).map(::withCreditStatus)
        }

    private fun withCreditStatus(row: AccountBalance): AccountBalance {
        val isOwedWhenNegative = types.byKey(row.account.typeKey)?.owedWhenNegative == true
        if (!isOwedWhenNegative) return row
        return row.copy(credit = CreditStatus.of(row.balance, row.account.creditLimit))
    }
}
