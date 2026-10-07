package org.aristonis.mywallet.domain.model

/**
 * A kind of account (cash / card / savings / …). Cosmetic classification, identified by [key].
 *
 * There is no display name here on purpose. This layer has no resources and no locale, so any name
 * it held would be one hardcoded language leaking upward — and the key is the identity anyway, so a
 * type cannot be broken by anything that happens to its text.
 */
data class AccountType(val key: String) {
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
        val SAVINGS = AccountType("savings")
        val BANK = AccountType("bank")
        val OTHER = AccountType("other")
        val all = listOf(CASH, CARD, SAVINGS, BANK, OTHER)
    }
}
