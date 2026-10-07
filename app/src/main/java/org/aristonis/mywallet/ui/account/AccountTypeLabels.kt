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
    AccountTypeRegistry.BuiltIns.SAVINGS.key to R.string.account_type_savings,
    AccountTypeRegistry.BuiltIns.BANK.key to R.string.account_type_bank,
    AccountTypeRegistry.BuiltIns.OTHER.key to R.string.account_type_other,
)

@Composable
fun accountTypeLabel(typeKey: String): String =
    accountTypeLabels[typeKey]?.let { stringResource(it) } ?: typeKey

@Composable
fun AccountType.label(): String = accountTypeLabel(key)
