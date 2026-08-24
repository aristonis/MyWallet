package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import java.time.LocalDate

/**
 * Records an income into an account. Cross-entity rules live here (the account must exist, the
 * amount must be in the account's currency, and the category pair must satisfy
 * [requireTransactionCategories]); the amount-positive rule is already enforced by
 * [Transaction.Income] itself.
 *
 * Dependencies are the ports, injected via the constructor. Call it like a function:
 * `recordIncome(accountId = 1, amount = ..., ...)`.
 */
class RecordIncome(
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val transactions: TransactionRepository,
) {
    suspend operator fun invoke(
        accountId: Long,
        amount: Money,
        categoryId: Long,
        date: LocalDate,
        subCategoryId: Long? = null,
        note: String? = null,
    ): Long {
        val account = accounts.findById(accountId)
            ?: throw WalletException.AccountNotFound(accountId)
        if (account.archived) throw WalletException.AccountArchived(accountId)
        if (amount.currencyCode != account.currencyCode) {
            throw WalletException.CurrencyMismatch(amount.currencyCode, account.currencyCode)
        }
        categories.requireTransactionCategories(categoryId, subCategoryId)

        return transactions.add(
            Transaction.Income(
                accountId = accountId,
                amount = amount,
                categoryId = categoryId,
                subCategoryId = subCategoryId,
                date = date,
                note = note,
            ),
        )
    }
}
