package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.AccountBalance
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.service.BalanceCalculator

/**
 * Live per-account balances. `combine` re-runs the calculator whenever accounts OR transactions
 * change, so the UI collecting this Flow updates itself — no manual refresh.
 */
class GetAccountBalances(
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
) {
    operator fun invoke(): Flow<List<AccountBalance>> =
        combine(accounts.observeAll(), transactions.observeAll()) { accountList, transactionList ->
            BalanceCalculator.balances(accountList, transactionList)
        }
}
