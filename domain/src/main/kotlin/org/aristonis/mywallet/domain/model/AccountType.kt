package org.aristonis.mywallet.domain.model

/**
 * A kind of account (cash / card / savings / …). Cosmetic classification, identified by [key].
 *
 * There is no display name here on purpose. This layer has no resources and no locale, so any name
 * it held would be one hardcoded language leaking upward — and the key is the identity anyway, so a
 * type cannot be broken by anything that happens to its text.
 *
 * [owedWhenNegative] marks a liability type such as a credit card: a negative balance there is money
 * the owner owes, so balances report how much is owed and how much credit is left. It is false for
 * every asset type, a debit card included, whose negative balance is an overdraft shown as it is and
 * never read as debt.
 */
data class AccountType(val key: String, val owedWhenNegative: Boolean = false) {
    init {
        require(key.isNotBlank()) { "account type key must not be blank" }
    }
}

/**
 * OCP registry of account types: adding a new type is a [register] insert — never an edit to a
 * shared switch/when. Seeded with the built-ins; the app can register more.
 */
class AccountTypeRegistry(initial: List<AccountType> = BuiltIns.all) {
    private val registry = LinkedHashMap<String, AccountType>()

    init { initial.forEach(::register) }

    fun register(type: AccountType) {
        registry[type.key] = type
    }

    fun all(): List<AccountType> = registry.values.toList()

    fun byKey(key: String): AccountType? = registry[key]

    object BuiltIns {
        val CASH = AccountType("cash")
        val CARD = AccountType("card")
        val CREDIT_CARD = AccountType("credit_card", owedWhenNegative = true)
        val SAVINGS = AccountType("savings")
        val BANK = AccountType("bank")
        val OTHER = AccountType("other")
        val all = listOf(CASH, CARD, CREDIT_CARD, SAVINGS, BANK, OTHER)
    }
}
