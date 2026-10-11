package org.aristonis.mywallet.domain.model

import java.math.BigDecimal
import java.time.LocalDate

/**
 * A recorded money movement, sealed into its kinds. Core invariants (amount > 0, no self-transfer,
 * rate > 0) are enforced here so illegal states are unrepresentable; use-cases surface them as
 * user-facing validation.
 *
 * The debt kinds hold a resolved debt id: the save finds or creates the debt first, so an entry read
 * back from its legs equals the one that was saved.
 */
sealed interface Transaction {
    val id: Long
    val date: LocalDate
    val note: String?

    data class Income(
        override val id: Long = 0,
        val accountId: Long,
        val amount: Money,
        val categoryId: Long,
        val subCategoryId: Long? = null,
        override val date: LocalDate,
        override val note: String? = null,
        /** The rate to the base currency this entry was recorded with; null means none is stored yet. */
        val rateToBase: BigDecimal? = null,
    ) : Transaction {
        init { require(amount.isPositive) { "income amount must be > 0" } }
    }

    data class Expense(
        override val id: Long = 0,
        val accountId: Long,
        val amount: Money,
        val categoryId: Long,
        val subCategoryId: Long? = null,
        override val date: LocalDate,
        override val note: String? = null,
        /** The rate to the base currency this entry was recorded with; null means none is stored yet. */
        val rateToBase: BigDecimal? = null,
    ) : Transaction {
        init { require(amount.isPositive) { "expense amount must be > 0" } }
    }

    /**
     * Money returned after an expense. The account receives [amount], while the expense category is
     * reduced by the same exact amount. It is deliberately its own kind: a refund is not income.
     */
    data class Refund(
        override val id: Long = 0,
        val accountId: Long,
        val amount: Money,
        val categoryId: Long,
        val subCategoryId: Long? = null,
        override val date: LocalDate,
        override val note: String? = null,
        /** The rate to the base currency this refund was recorded with; null means none is stored yet. */
        val rateToBase: BigDecimal? = null,
    ) : Transaction {
        init { require(amount.isPositive) { "refund amount must be > 0" } }
    }

    data class Transfer(
        override val id: Long = 0,
        val sourceAccountId: Long,
        val destAccountId: Long,
        val sourceAmount: Money,
        val destAmount: Money,
        val rateUsed: BigDecimal,
        override val date: LocalDate,
        override val note: String? = null,
    ) : Transaction {
        init {
            require(sourceAccountId != destAccountId) { "cannot transfer to the same account" }
            require(sourceAmount.isPositive) { "transfer sourceAmount must be > 0" }
            require(destAmount.isPositive) { "transfer destAmount must be > 0" }
            require(rateUsed.signum() > 0) { "transfer rateUsed must be > 0" }
        }
    }

    /** Money borrowed into an account: the account and what I owe both grow by [amount]. */
    data class Borrow(
        override val id: Long = 0,
        val accountId: Long,
        val debtId: Long,
        val amount: Money,
        override val date: LocalDate,
        override val note: String? = null,
    ) : Transaction {
        init { require(amount.isPositive) { "borrow amount must be > 0" } }
    }

    /** Money lent out of an account: the account shrinks and what is owed to me grows by [amount]. */
    data class Lend(
        override val id: Long = 0,
        val accountId: Long,
        val debtId: Long,
        val amount: Money,
        override val date: LocalDate,
        override val note: String? = null,
    ) : Transaction {
        init { require(amount.isPositive) { "lend amount must be > 0" } }
    }

    /**
     * A debt that existed before the app: only the debt grows. No money moves and nothing is earned
     * or spent, so it is an opening balance, not income or expense.
     */
    data class OwedBefore(
        override val id: Long = 0,
        val debtId: Long,
        val side: DebtSide,
        val amount: Money,
        override val date: LocalDate,
        override val note: String? = null,
    ) : Transaction {
        init { require(amount.isPositive) { "owed-before amount must be > 0" } }
    }

    /** Something bought now and paid later: what I owe grows and the expense counts today. */
    data class CreditPurchase(
        override val id: Long = 0,
        val debtId: Long,
        val amount: Money,
        val categoryId: Long,
        val subCategoryId: Long? = null,
        override val date: LocalDate,
        override val note: String? = null,
        /** The rate to the base currency this entry was recorded with; null means none is stored yet. */
        val rateToBase: BigDecimal? = null,
    ) : Transaction {
        init { require(amount.isPositive) { "credit purchase amount must be > 0" } }
    }

    /** Income earned now and paid later: what is owed to me grows and the income counts today. */
    data class EarnedUnpaid(
        override val id: Long = 0,
        val debtId: Long,
        val amount: Money,
        val categoryId: Long,
        val subCategoryId: Long? = null,
        override val date: LocalDate,
        override val note: String? = null,
        /** The rate to the base currency this entry was recorded with; null means none is stored yet. */
        val rateToBase: BigDecimal? = null,
    ) : Transaction {
        init { require(amount.isPositive) { "earned-unpaid amount must be > 0" } }
    }

    /**
     * Paying back what I owe ([side] I_OWE, money out) or collecting what is owed to me ([side]
     * OWED_TO_ME, money in). Only the principal settles the debt; interest is an expense when paying
     * and income when collecting.
     *
     * @property accountAmount What moved through the account, in its currency: principal plus interest
     *   when the account and debt share a currency.
     * @property principal How much the debt shrinks, in the debt's currency. Zero for interest only.
     * @property interest In the account's currency; null when there is none.
     * @property pairRate The rate between the account and debt currencies; null when they are the same.
     *   On both sides the debt amount equals the account-side principal times this rate. The save
     *   prices the principal with it; the reader keeps the stored principal and never derives it again.
     */
    data class Repayment(
        override val id: Long = 0,
        val side: DebtSide,
        val accountId: Long,
        val debtId: Long,
        val accountAmount: Money,
        val principal: Money,
        val interest: Money? = null,
        val interestCategoryId: Long? = null,
        val interestSubCategoryId: Long? = null,
        /** The rate to the base currency the interest was recorded with; null means none is stored yet. */
        val interestRateToBase: BigDecimal? = null,
        val pairRate: BigDecimal? = null,
        override val date: LocalDate,
        override val note: String? = null,
    ) : Transaction {
        init {
            require(accountAmount.isPositive) { "repayment accountAmount must be > 0" }
            require(!principal.isNegative) { "repayment principal must be >= 0" }
            require(interest == null || interest.isPositive) { "repayment interest must be absent or > 0" }
            require(principal.isPositive || interest != null) { "a repayment must pay off principal or interest" }
        }
    }

    /**
     * Settling the rest of a debt without money: a loss when what was owed to me will never come
     * ([side] OWED_TO_ME, expense), forgiven when what I owe is let go ([side] I_OWE, income).
     */
    data class WriteOff(
        override val id: Long = 0,
        val side: DebtSide,
        val debtId: Long,
        val amount: Money,
        val categoryId: Long,
        val subCategoryId: Long? = null,
        /** The rate to the base currency this entry was recorded with; null means none is stored yet. */
        val rateToBase: BigDecimal? = null,
        override val date: LocalDate,
        override val note: String? = null,
    ) : Transaction {
        init { require(amount.isPositive) { "write-off amount must be > 0" } }
    }
}
