package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.model.Transaction
import java.math.BigDecimal

/**
 * Pure entity ↔ domain mappers (no Room runtime) — unit-tested on the JVM. Money is stored as an
 * exact TEXT string (`toPlainString`, never scientific notation) plus its currency code.
 */

// --- Account ---
fun AccountEntity.toDomain(): Account = Account(
    id = id,
    name = name,
    typeKey = typeKey,
    currencyCode = currencyCode,
    openingBalance = Money.of(openingBalanceAmount, currencyCode),
    archived = archived,
    sortOrder = sortOrder,
)

fun Account.toEntity(): AccountEntity = AccountEntity(
    id = id,
    name = name,
    typeKey = typeKey,
    currencyCode = currencyCode,
    openingBalanceAmount = openingBalance.amount.toPlainString(),
    archived = archived,
    sortOrder = sortOrder,
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
    Settings(baseCurrencyCode = baseCurrencyCode, theme = ThemePreference.valueOf(theme), schemaVersion = schemaVersion)

fun Settings.toEntity(): SettingsEntity =
    SettingsEntity(baseCurrencyCode = baseCurrencyCode, theme = theme.name, schemaVersion = schemaVersion)

// --- Transaction (single-table inheritance) ---
fun TransactionEntity.toDomain(): Transaction = when (type) {
    "INCOME" -> Transaction.Income(
        id = id,
        accountId = primaryAccountId,
        amount = Money.of(primaryAmount, primaryCurrency),
        categoryId = requireNotNull(categoryId) { "income row $id missing categoryId" },
        subCategoryId = subCategoryId,
        date = date,
        note = note,
    )

    "EXPENSE" -> Transaction.Expense(
        id = id,
        accountId = primaryAccountId,
        amount = Money.of(primaryAmount, primaryCurrency),
        categoryId = requireNotNull(categoryId) { "expense row $id missing categoryId" },
        subCategoryId = subCategoryId,
        date = date,
        note = note,
    )

    "TRANSFER" -> Transaction.Transfer(
        id = id,
        sourceAccountId = primaryAccountId,
        destAccountId = requireNotNull(secondaryAccountId) { "transfer row $id missing destAccountId" },
        sourceAmount = Money.of(primaryAmount, primaryCurrency),
        destAmount = Money.of(requireNotNull(secondaryAmount), requireNotNull(secondaryCurrency)),
        rateUsed = BigDecimal(requireNotNull(rateUsed)),
        date = date,
        note = note,
    )

    else -> error("unknown transaction type: $type")
}

fun Transaction.toEntity(): TransactionEntity = when (this) {
    is Transaction.Income -> TransactionEntity(
        id = id, type = "INCOME", date = date, note = note,
        primaryAccountId = accountId,
        primaryAmount = amount.amount.toPlainString(),
        primaryCurrency = amount.currencyCode,
        categoryId = categoryId,
        subCategoryId = subCategoryId,
    )

    is Transaction.Expense -> TransactionEntity(
        id = id, type = "EXPENSE", date = date, note = note,
        primaryAccountId = accountId,
        primaryAmount = amount.amount.toPlainString(),
        primaryCurrency = amount.currencyCode,
        categoryId = categoryId,
        subCategoryId = subCategoryId,
    )

    is Transaction.Transfer -> TransactionEntity(
        id = id, type = "TRANSFER", date = date, note = note,
        primaryAccountId = sourceAccountId,
        primaryAmount = sourceAmount.amount.toPlainString(),
        primaryCurrency = sourceAmount.currencyCode,
        secondaryAccountId = destAccountId,
        secondaryAmount = destAmount.amount.toPlainString(),
        secondaryCurrency = destAmount.currencyCode,
        rateUsed = rateUsed.toPlainString(),
    )
}
