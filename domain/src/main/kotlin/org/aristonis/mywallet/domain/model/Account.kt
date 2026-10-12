package org.aristonis.mywallet.domain.model

/**
 * A container of money in exactly one currency. [openingBalance] seeds the balance and is EXCLUDED
 * from income/expense tracking, since it is money already held rather than earned or spent. Its
 * currency must match the account currency.
 *
 * [creditLimit] is the most a credit account may owe before it reads as over the limit; null means
 * no limit is set. When present it is positive and in the account currency.
 *
 * [defaultFee] is the optional fee suggested when recording an entry from this account. A fixed
 * fee uses the account currency; a zero value means no fee.
 */
data class Account(
    val id: Long = 0,
    val name: String,
    val typeKey: String,
    val currencyCode: String,
    val openingBalance: Money,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val creditLimit: Money? = null,
    val defaultFee: FeeRule? = null,
) {
    init {
        require(name.isNotBlank()) { "account name must not be blank" }
        require(typeKey.isNotBlank()) { "account typeKey must not be blank" }
        require(currencyCode.isNotBlank()) { "account currencyCode must not be blank" }
        require(openingBalance.currencyCode == currencyCode) {
            "openingBalance currency ${openingBalance.currencyCode} != account currency $currencyCode"
        }
        creditLimit?.let { limit ->
            require(limit.currencyCode == currencyCode) {
                "creditLimit currency ${limit.currencyCode} != account currency $currencyCode"
            }
            require(limit.isPositive) { "creditLimit must be positive, was ${limit.amount}" }
        }
    }
}
