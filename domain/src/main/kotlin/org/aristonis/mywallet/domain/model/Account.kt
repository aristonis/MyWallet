package org.aristonis.mywallet.domain.model

/**
 * A container of money in exactly one currency. [openingBalance] seeds the balance and is EXCLUDED
 * from income/expense tracking (D11). Its currency must match the account currency.
 */
data class Account(
    val id: Long = 0,
    val name: String,
    val typeKey: String,
    val currencyCode: String,
    val openingBalance: Money,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "account name must not be blank" }
        require(typeKey.isNotBlank()) { "account typeKey must not be blank" }
        require(currencyCode.isNotBlank()) { "account currencyCode must not be blank" }
        require(openingBalance.currencyCode == currencyCode) {
            "openingBalance currency ${openingBalance.currencyCode} != account currency $currencyCode"
        }
    }
}
