package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.AccountBalance
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.service.BalanceCalculator

/**
 * Live per-account balances: each account's opening balance plus its account legs. Only the leg
 * projection is read, never whole entries. `combine` re-runs the sum whenever accounts or legs
 * change, so a screen collecting this updates itself.
 */
class GetAccountBalances(
    private val accounts: AccountRepository,
    private val ledger: LedgerQueries,
) {
    operator fun invoke(): Flow<List<AccountBalance>> =
        combine(accounts.observeAll(), ledger.observeAccountLegs()) { accountList, legs ->
            BalanceCalculator.balances(accountList, legs)
        }
}
