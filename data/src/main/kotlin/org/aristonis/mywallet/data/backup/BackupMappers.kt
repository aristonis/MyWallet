package org.aristonis.mywallet.data.backup

import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.TOP_LEVEL_PARENT_ID
import org.aristonis.mywallet.data.db.TransactionEntity

/**
 * Pure entity ↔ backup-DTO mappers (no Room runtime) — unit-tested on the JVM. Both sides are flat
 * rows with matching field names, so each mapper is a straight copy; the DTO layer is what keeps
 * the backup file decoupled from the Room entities.
 */

// --- Account ---
fun AccountEntity.toDto(): AccountDto = AccountDto(
    id = id, name = name, typeKey = typeKey, currencyCode = currencyCode,
    openingBalanceAmount = openingBalanceAmount, archived = archived, sortOrder = sortOrder,
)

fun AccountDto.toEntity(): AccountEntity = AccountEntity(
    id = id, name = name, typeKey = typeKey, currencyCode = currencyCode,
    openingBalanceAmount = openingBalanceAmount, archived = archived, sortOrder = sortOrder,
)

// --- Transaction ---
fun TransactionEntity.toDto(): TransactionDto = TransactionDto(
    id = id, type = type, date = date, note = note,
    primaryAccountId = primaryAccountId, primaryAmount = primaryAmount, primaryCurrency = primaryCurrency,
    categoryId = categoryId, subCategoryId = subCategoryId,
    secondaryAccountId = secondaryAccountId, secondaryAmount = secondaryAmount,
    secondaryCurrency = secondaryCurrency, rateUsed = rateUsed,
)

fun TransactionDto.toEntity(): TransactionEntity = TransactionEntity(
    id = id, type = type, date = date, note = note,
    primaryAccountId = primaryAccountId, primaryAmount = primaryAmount, primaryCurrency = primaryCurrency,
    categoryId = categoryId, subCategoryId = subCategoryId,
    secondaryAccountId = secondaryAccountId, secondaryAmount = secondaryAmount,
    secondaryCurrency = secondaryCurrency, rateUsed = rateUsed,
)

// --- Category ---
// The backup file keeps "no parent" as JSON null: it stays readable by hand and portable across
// any future storage change, so the sentinel never leaks into the format.
fun CategoryEntity.toDto(): CategoryDto = CategoryDto(
    id = id,
    name = name,
    kind = kind,
    parentId = parentId.takeIf { it != TOP_LEVEL_PARENT_ID },
    systemKey = systemKey,
)

fun CategoryDto.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    kind = kind,
    parentId = parentId ?: TOP_LEVEL_PARENT_ID,
    systemKey = systemKey,
)

// --- Currency ---
fun CurrencyEntity.toDto(): CurrencyDto = CurrencyDto(code = code, symbol = symbol, decimalPlaces = decimalPlaces)

fun CurrencyDto.toEntity(): CurrencyEntity = CurrencyEntity(code = code, symbol = symbol, decimalPlaces = decimalPlaces)

// --- Rate ---
fun RateEntity.toDto(): RateDto = RateDto(currencyCode = currencyCode, rateToBase = rateToBase)

fun RateDto.toEntity(): RateEntity = RateEntity(currencyCode = currencyCode, rateToBase = rateToBase)

// --- Settings ---
fun SettingsEntity.toDto(): SettingsDto =
    SettingsDto(id = id, baseCurrencyCode = baseCurrencyCode, theme = theme, schemaVersion = schemaVersion)

fun SettingsDto.toEntity(): SettingsEntity =
    SettingsEntity(id = id, baseCurrencyCode = baseCurrencyCode, theme = theme, schemaVersion = schemaVersion)
