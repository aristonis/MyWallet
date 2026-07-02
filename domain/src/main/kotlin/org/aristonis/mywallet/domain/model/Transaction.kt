package org.aristonis.mywallet.domain.model

import java.math.BigDecimal
import java.time.LocalDate

/**
 * A recorded money movement. Sealed into the three kinds. Core invariants (amount > 0, no
 * self-transfer, rate > 0) are enforced here so illegal states are unrepresentable; use-cases
 * surface them as user-facing validation (NFR-9).
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
    ) : Transaction {
        init { require(amount.isPositive) { "expense amount must be > 0" } }
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
}
