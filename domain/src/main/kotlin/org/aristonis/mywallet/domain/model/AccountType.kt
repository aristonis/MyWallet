package org.aristonis.mywallet.domain.model

/** A kind of account (Cash / Card / Savings / …). Cosmetic classification; keyed by [key]. */
data class AccountType(val key: String, val displayName: String) {
    init {
        require(key.isNotBlank()) { "account type key must not be blank" }
        require(displayName.isNotBlank()) { "account type displayName must not be blank" }
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
        val CASH = AccountType("cash", "Cash")
        val CARD = AccountType("card", "Card")
        val SAVINGS = AccountType("savings", "Savings")
        val BANK = AccountType("bank", "Bank")
        val OTHER = AccountType("other", "Other")
        val all = listOf(CASH, CARD, SAVINGS, BANK, OTHER)
    }
}
