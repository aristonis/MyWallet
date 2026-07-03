package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.service.CurrencyConverter
import java.math.BigDecimal

/**
 * Edits an existing transaction in place (its id is preserved). Re-runs the same cross-entity rules
 * as the Record* use-cases — the account must exist, be live (not archived), and hold money in its
 * own currency; income/expense categories must exist — so an edit can never leave an invalid row.
 *
 * A transfer is a historical fact, so its destination leg is recomputed from the (possibly new)
 * source amount and the transfer's ORIGINAL stored rate (preserve-rate semantics): editing only a
 * note or date never moves money, and to apply a different rate you delete and re-add. Balances
 * recompute for free via the reactive read path.
 */
class UpdateTransaction(
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val currencies: CurrencyRepository,
    private val transactions: TransactionRepository,
) {
    suspend operator fun invoke(transaction: Transaction) {
        val existing = transactions.findById(transaction.id)
            ?: throw WalletException.TransactionNotFound(transaction.id)
        when (transaction) {
            is Transaction.Income ->
                validateAndUpdate(transaction.accountId, transaction.amount, transaction.categoryId, transaction)

            is Transaction.Expense ->
                validateAndUpdate(transaction.accountId, transaction.amount, transaction.categoryId, transaction)

            is Transaction.Transfer -> {
                requireLiveAccountHolds(transaction.sourceAccountId, transaction.sourceAmount)
                val dest = accounts.findById(transaction.destAccountId)
                    ?: throw WalletException.AccountNotFound(transaction.destAccountId)
                if (dest.archived) throw WalletException.AccountArchived(transaction.destAccountId)
                requireUnchangedCurrencyPair(existing, transaction.sourceAmount.currencyCode, dest.currencyCode)
                val destAmount = convertAtStoredRate(
                    source = transaction.sourceAmount,
                    rate = transaction.rateUsed,
                    destCurrency = dest.currencyCode,
                )
                transactions.update(transaction.copy(destAmount = destAmount))
            }
        }
    }

    /** Re-validates an income/expense (its account holds the amount, its category exists) then persists it. */
    private suspend fun validateAndUpdate(accountId: Long, amount: Money, categoryId: Long, transaction: Transaction) {
        requireLiveAccountHolds(accountId, amount)
        requireCategory(categoryId)
        transactions.update(transaction)
    }

    /** [accountId] must exist, be live (not archived), and hold money in [amount]'s currency. */
    private suspend fun requireLiveAccountHolds(accountId: Long, amount: Money) {
        val account = accounts.findById(accountId)
            ?: throw WalletException.AccountNotFound(accountId)
        if (account.archived) throw WalletException.AccountArchived(accountId)
        if (amount.currencyCode != account.currencyCode) {
            throw WalletException.CurrencyMismatch(amount.currencyCode, account.currencyCode)
        }
    }

    private suspend fun requireCategory(categoryId: Long) {
        categories.findById(categoryId) ?: throw WalletException.CategoryNotFound(categoryId)
    }

    /**
     * A transfer's stored rate is tied to its currency pair, so an edit must keep that pair — change
     * the accounts or rate by deleting and re-adding. Compares the persisted pair to the edited one.
     */
    private fun requireUnchangedCurrencyPair(existing: Transaction, newSource: String, newDest: String) {
        val storedPair = (existing as? Transaction.Transfer)
            ?.let { it.sourceAmount.currencyCode to it.destAmount.currencyCode }
        if (storedPair != (newSource to newDest)) {
            throw WalletException.TransferCurrencyPairChanged(existing.id)
        }
    }

    /**
     * Re-derives the destination leg at the transfer's own [rate], rounded to the destination
     * currency's decimals with the app's single rounding mode. A same-currency transfer copies 1:1.
     */
    private suspend fun convertAtStoredRate(source: Money, rate: BigDecimal, destCurrency: String): Money {
        if (source.currencyCode == destCurrency) return Money.of(source.amount, destCurrency)
        val decimals = currencies.findByCode(destCurrency)?.decimalPlaces
            ?: throw WalletException.CurrencyNotFound(destCurrency)
        val converted = source.amount.multiply(rate).setScale(decimals, CurrencyConverter.ROUNDING)
        if (converted.signum() == 0) throw WalletException.AmountRoundsToZero(destCurrency)
        return Money.of(converted, destCurrency)
    }
}
