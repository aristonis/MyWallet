package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import java.time.LocalDate

/** Records an expense out of an account. Same rules as [RecordIncome], different transaction kind. */
class RecordExpense(
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
        categories.findById(categoryId)
            ?: throw WalletException.CategoryNotFound(categoryId)

        return transactions.add(
            Transaction.Expense(
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
