package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.DebtEntryGuard
import org.aristonis.mywallet.domain.service.DebtGuard
import java.math.BigDecimal
import java.time.LocalDate

/** Prices and records a repayment atomically, keeping the debt principal exact on a full payoff. */
class RecordRepayment(
    private val runner: TransactionRunner,
    debts: DebtRepository,
    ledger: LedgerQueries,
    private val entries: EntryRepository,
    private val accounts: AccountRepository,
    private val fx: FxRepository,
    private val saveEntry: SaveEntry,
) {
    private val debtGuard = DebtEntryGuard(debts, ledger)

    /** Null [principal] pays the available rest read inside this transaction; interest is additional. */
    suspend operator fun invoke(
        debtId: Long,
        accountId: Long,
        principal: Money?,
        date: LocalDate,
        typedRates: Map<String, BigDecimal> = emptyMap(),
        accountPrincipal: Money? = null,
        pairRate: BigDecimal? = null,
        interest: Money? = null,
        interestCategoryId: Long? = null,
        interestSubCategoryId: Long? = null,
        note: String? = null,
        id: Long = 0,
    ): SavedEntry = runner.inTransaction {
        val old = oldRepayment(id)
        val debt = debtGuard.requireDebt(debtId)
        val account = liveAccount(accountId)
        val resolvedPrincipal = principalWithinAllowance(debt, principal, old, interest)
        requireInterest(interest, account.currencyCode)
        // An edit that names no category keeps the stored one instead of falling back to the role default.
        val storedCategory = old?.takeIf { interestCategoryId == null && interest != null && it.interest != null }
        val category = if (storedCategory != null) {
            storedCategory.interestCategoryId to storedCategory.interestSubCategoryId
        } else {
            interestCategoryId to interestSubCategoryId
        }
        val priced = RepaymentPricing(fx.currentFx(), typedRates).price(
            resolvedPrincipal, account.currencyCode, accountPrincipal, pairRate, old,
        )
        saveEntry(
            Transaction.Repayment(
                id = id, side = debt.side, accountId = accountId, debtId = debt.id,
                accountAmount = interest?.let { priced.accountPrincipal + it } ?: priced.accountPrincipal,
                principal = resolvedPrincipal, pairRate = priced.pairRate,
                interest = interest, interestCategoryId = category.first,
                interestSubCategoryId = category.second, date = date, note = note,
            ),
            typedRates,
        )
    }

    private suspend fun oldRepayment(id: Long): Transaction.Repayment? {
        require(id >= 0) { "a repayment id must not be negative" }
        if (id == 0L) return null
        val old = entries.findById(id) ?: throw WalletException.TransactionNotFound(id)
        require(old is Transaction.Repayment) { "only a repayment can be edited as a repayment" }
        return old
    }

    private suspend fun liveAccount(id: Long): Account {
        val account = accounts.findById(id) ?: throw WalletException.AccountNotFound(id)
        if (account.archived) throw WalletException.AccountArchived(id)
        return account
    }

    private suspend fun principalWithinAllowance(
        debt: Debt,
        principal: Money?,
        old: Transaction.Repayment?,
        interest: Money?,
    ): Money {
        val current = debtGuard.remaining(setOf(debt.id))[debt.id]?.remaining ?: Money.zero(debt.currencyCode)
        val available = if (old?.debtId == debt.id) current + old.principal else current
        if (principal == null) {
            DebtGuard.check(debt, available)
            if (available.isZero && interest == null) throw WalletException.DebtHasNoRemaining(debt.counterparty)
        }
        val resolved = principal ?: available
        if (resolved.currencyCode != debt.currencyCode) {
            throw WalletException.CurrencyMismatch(resolved.currencyCode, debt.currencyCode)
        }
        require(!resolved.isNegative) { "repayment principal must not be negative" }
        if (resolved > available) throw WalletException.RepaymentExceedsRemaining(available)
        return resolved
    }

    private fun requireInterest(interest: Money?, currencyCode: String) {
        if (interest == null) return
        if (interest.currencyCode != currencyCode) {
            throw WalletException.CurrencyMismatch(interest.currencyCode, currencyCode)
        }
        require(interest.isPositive) { "repayment interest must be positive" }
    }
}
