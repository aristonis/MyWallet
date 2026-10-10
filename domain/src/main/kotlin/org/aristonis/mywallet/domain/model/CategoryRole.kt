package org.aristonis.mywallet.domain.model

/**
 * A job a built-in category does for the app: the category its interest, fee and written-off-debt
 * legs are filed under when the owner picks none.
 *
 * The enum NAME is the text stored in `category_roles.role`, and it is frozen once a release ships
 * it: renaming a constant would orphan every stored binding. [literalName] and [kind] are the name
 * and kind of the pre-made category the role starts bound to; they match the seed and the upgrade.
 */
enum class CategoryRole(val literalName: String, val kind: CategoryKind) {
    INTEREST_IN("Interest", CategoryKind.INCOME),
    INTEREST_OUT("Interest", CategoryKind.EXPENSE),
    FEES("Fees", CategoryKind.EXPENSE),
    LOST_DEBT("Lost debt", CategoryKind.EXPENSE),
    DEBT_FORGIVEN("Debt forgiven", CategoryKind.INCOME),
}
