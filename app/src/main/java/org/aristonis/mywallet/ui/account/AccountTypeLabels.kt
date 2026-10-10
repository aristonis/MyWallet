package org.aristonis.mywallet.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.AccountType
import org.aristonis.mywallet.domain.model.AccountTypeRegistry

/**
 * What each account type is called on screen.
 *
 * The domain holds only the key, because the key is the identity and a display name is neither
 * stable nor translatable there. Account types are an open registry, so this is a lookup rather than
 * a `when`: naming a newly registered type is an insert here, and a type nobody named still renders
 * — as its key, which is at least honest, instead of as a blank.
 */
private val accountTypeLabels: Map<String, Int> = mapOf(
    AccountTypeRegistry.BuiltIns.CASH.key to R.string.account_type_cash,
    AccountTypeRegistry.BuiltIns.CARD.key to R.string.account_type_card,
    AccountTypeRegistry.BuiltIns.CREDIT_CARD.key to R.string.account_type_credit_card,
    AccountTypeRegistry.BuiltIns.SAVINGS.key to R.string.account_type_savings,
    AccountTypeRegistry.BuiltIns.BANK.key to R.string.account_type_bank,
    AccountTypeRegistry.BuiltIns.OTHER.key to R.string.account_type_other,
)

/** The label resource for [typeKey], or null for a type nobody has named yet. */
fun accountTypeLabelRes(typeKey: String): Int? = accountTypeLabels[typeKey]

/**
 * The hint shown under the opening balance field, only for a type whose negative balance is money
 * owed (a credit card): there the opening balance is entered as a negative amount, which is easy to
 * get backwards. Null for every other type, and for a type the registry does not know.
 */
fun openingBalanceHintRes(typeKey: String): Int? =
    R.string.account_opening_balance_owed_hint.takeIf { isOwedWhenNegative(typeKey) }

/** True when [typeKey] is a built-in type whose negative balance is money owed; unknown types are not. */
fun isOwedWhenNegative(typeKey: String): Boolean =
    AccountTypeRegistry.BuiltIns.all.firstOrNull { it.key == typeKey }?.owedWhenNegative == true

@Composable
fun accountTypeLabel(typeKey: String): String =
    accountTypeLabelRes(typeKey)?.let { stringResource(it) } ?: typeKey

@Composable
fun AccountType.label(): String = accountTypeLabel(key)
