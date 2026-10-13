package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.FeeRule
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.ThemePreference
import java.math.BigDecimal

/**
 * Pure entity ↔ domain mappers (no Room runtime) — unit-tested on the JVM. Money is stored as an
 * exact TEXT string (`toPlainString`, never scientific notation) plus its currency code. Entries
 * have their own mappers in LegMappers.kt.
 */

// --- Account ---
// The credit limit is stored as exact text in the account currency, null when no limit is set. A
// default fee value is exact decimal text and must have its mode alongside it.
fun AccountEntity.toDomain(): Account = Account(
    id = id,
    name = name,
    typeKey = typeKey,
    currencyCode = currencyCode,
    openingBalance = Money.of(openingBalanceAmount, currencyCode),
    archived = archived,
    sortOrder = sortOrder,
    creditLimit = creditLimit?.let { Money.of(it, currencyCode) },
    defaultFee = readDefaultFee(),
)

/** Both fee columns are null or both are valid; any other stored shape makes the account row unreadable. */
private fun AccountEntity.readDefaultFee(): FeeRule? {
    if (defaultFeeMode == null && defaultFeeValue == null) return null
    return try {
        FeeRule(
            mode = FeeMode.valueOf(requireNotNull(defaultFeeMode) { "default fee mode missing" }),
            value = BigDecimal(requireNotNull(defaultFeeValue) { "default fee value missing" }),
        )
    } catch (e: IllegalArgumentException) {
        throw WalletException.UnreadableRow(id, ACCOUNT_KIND, e)
    } catch (e: WalletException.FeePercentAboveHundred) {
        throw WalletException.UnreadableRow(id, ACCOUNT_KIND, e)
    }
}

private const val ACCOUNT_KIND = "account"

fun Account.toEntity(): AccountEntity = AccountEntity(
    id = id,
    name = name,
    typeKey = typeKey,
    currencyCode = currencyCode,
    openingBalanceAmount = openingBalance.amount.toPlainString(),
    archived = archived,
    sortOrder = sortOrder,
    creditLimit = creditLimit?.amount?.toPlainString(),
    defaultFeeMode = defaultFee?.mode?.name,
    defaultFeeValue = defaultFee?.value?.toPlainString(),
)

// --- Category ---
// The domain models "no parent" as null; storage uses a sentinel so the sibling-uniqueness index
// actually constrains top-level rows, because SQLite treats every NULL in a unique index as distinct.
fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    kind = CategoryKind.valueOf(kind),
    parentId = parentId.takeIf { it != TOP_LEVEL_PARENT_ID },
    systemKey = systemKey,
)

fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    kind = kind.name,
    parentId = parentId ?: TOP_LEVEL_PARENT_ID,
    systemKey = systemKey,
)

// --- Currency ---
fun CurrencyEntity.toDomain(): Currency = Currency(code = code, symbol = symbol, decimalPlaces = decimalPlaces)

fun Currency.toEntity(): CurrencyEntity = CurrencyEntity(code = code, symbol = symbol, decimalPlaces = decimalPlaces)

// --- ExchangeRate ---
fun RateEntity.toDomain(): ExchangeRate = ExchangeRate(currencyCode = currencyCode, rateToBase = BigDecimal(rateToBase))

fun ExchangeRate.toEntity(): RateEntity = RateEntity(currencyCode = currencyCode, rateToBase = rateToBase.toPlainString())

// --- Settings ---
fun SettingsEntity.toDomain(): Settings =
    Settings(
        baseCurrencyCode = baseCurrencyCode,
        theme = ThemePreference.valueOf(theme),
        schemaVersion = schemaVersion,
        saveTransactionRates = saveTransactionRates,
    )

fun Settings.toEntity(): SettingsEntity =
    SettingsEntity(
        baseCurrencyCode = baseCurrencyCode,
        theme = theme.name,
        schemaVersion = schemaVersion,
        saveTransactionRates = saveTransactionRates,
    )
