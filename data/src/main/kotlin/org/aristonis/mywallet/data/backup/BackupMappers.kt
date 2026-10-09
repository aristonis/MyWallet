package org.aristonis.mywallet.data.backup

import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CategoryRoleEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.DebtEntity
import org.aristonis.mywallet.data.db.DeductionItemAutoCategoryEntity
import org.aristonis.mywallet.data.db.DeductionItemEntity
import org.aristonis.mywallet.data.db.EntryEntity
import org.aristonis.mywallet.data.db.EntryLegEntity
import org.aristonis.mywallet.data.db.IncomeDeductionLineEntity
import org.aristonis.mywallet.data.db.LegacyUnreadableRowEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.TOP_LEVEL_PARENT_ID

/**
 * Pure entity ↔ backup-DTO mappers (no Room runtime) — unit-tested on the JVM. Both sides are flat
 * rows with matching field names, so each mapper is a straight copy; the DTO layer is what keeps
 * the backup file decoupled from the Room entities.
 */

// --- Account ---
fun AccountEntity.toDto(): AccountDtoV2 = AccountDtoV2(
    id = id, name = name, typeKey = typeKey, currencyCode = currencyCode,
    openingBalanceAmount = openingBalanceAmount, archived = archived, sortOrder = sortOrder,
    creditLimit = creditLimit, defaultFeeMode = defaultFeeMode, defaultFeeValue = defaultFeeValue,
)

fun AccountDtoV2.toEntity(): AccountEntity = AccountEntity(
    id = id, name = name, typeKey = typeKey, currencyCode = currencyCode,
    openingBalanceAmount = openingBalanceAmount, archived = archived, sortOrder = sortOrder,
    creditLimit = creditLimit, defaultFeeMode = defaultFeeMode, defaultFeeValue = defaultFeeValue,
)

// --- Entry and its legs ---
fun EntryEntity.toDto(): EntryDto = EntryDto(id = id, kind = kind, date = date, note = note, pairRate = pairRate)

fun EntryDto.toEntity(): EntryEntity = EntryEntity(id = id, kind = kind, date = date, note = note, pairRate = pairRate)

// A leg's own id is internal and not in the file; a restore numbers the legs in list order.
fun EntryLegEntity.toDto(): LegDto = LegDto(
    entryId = entryId, role = role, position = position,
    accountId = accountId, debtId = debtId, categoryId = categoryId, subCategoryId = subCategoryId,
    amount = amount, currencyCode = currencyCode, rateToBase = rateToBase,
)

fun LegDto.toEntity(): EntryLegEntity = EntryLegEntity(
    entryId = entryId, role = role, position = position,
    accountId = accountId, debtId = debtId, categoryId = categoryId, subCategoryId = subCategoryId,
    amount = amount, currencyCode = currencyCode, rateToBase = rateToBase,
)

// --- Debt ---
fun DebtEntity.toDto(): DebtDto = DebtDto(
    id = id, counterparty = counterparty, counterpartyKey = counterpartyKey, side = side,
    currencyCode = currencyCode, dueDate = dueDate, note = note,
)

fun DebtDto.toEntity(): DebtEntity = DebtEntity(
    id = id, counterparty = counterparty, counterpartyKey = counterpartyKey, side = side,
    currencyCode = currencyCode, dueDate = dueDate, note = note,
)

// --- Deductions ---
fun DeductionItemEntity.toDto(): DeductionItemDto = DeductionItemDto(
    id = id, name = name, nameKey = nameKey, mode = mode, value = value, valueCurrencyCode = valueCurrencyCode,
    timing = timing, expenseCategoryId = expenseCategoryId, enabled = enabled, sortOrder = sortOrder,
)

fun DeductionItemDto.toEntity(): DeductionItemEntity = DeductionItemEntity(
    id = id, name = name, nameKey = nameKey, mode = mode, value = value, valueCurrencyCode = valueCurrencyCode,
    timing = timing, expenseCategoryId = expenseCategoryId, enabled = enabled, sortOrder = sortOrder,
)

fun DeductionItemAutoCategoryEntity.toDto(): DeductionAutoCategoryDto =
    DeductionAutoCategoryDto(itemId = itemId, incomeCategoryId = incomeCategoryId)

fun DeductionAutoCategoryDto.toEntity(): DeductionItemAutoCategoryEntity =
    DeductionItemAutoCategoryEntity(itemId = itemId, incomeCategoryId = incomeCategoryId)

fun IncomeDeductionLineEntity.toDto(): DeductionLineDto = DeductionLineDto(
    id = id, entryId = entryId, itemId = itemId, position = position, name = name, mode = mode,
    value = value, valueCurrencyCode = valueCurrencyCode, timing = timing,
)

fun DeductionLineDto.toEntity(): IncomeDeductionLineEntity = IncomeDeductionLineEntity(
    id = id, entryId = entryId, itemId = itemId, position = position, name = name, mode = mode,
    value = value, valueCurrencyCode = valueCurrencyCode, timing = timing,
)

// --- Category role ---
fun CategoryRoleEntity.toDto(): CategoryRoleDto = CategoryRoleDto(role = role, categoryId = categoryId)

fun CategoryRoleDto.toEntity(): CategoryRoleEntity = CategoryRoleEntity(role = role, categoryId = categoryId)

// --- Raw copy of an old row, written back verbatim ---
fun LegacyUnreadableRowEntity.toDto(): LegacyRowDto = LegacyRowDto(
    id = id, type = type, date = date, note = note,
    primaryAccountId = primaryAccountId, primaryAmount = primaryAmount, primaryCurrency = primaryCurrency,
    categoryId = categoryId, subCategoryId = subCategoryId,
    secondaryAccountId = secondaryAccountId, secondaryAmount = secondaryAmount,
    secondaryCurrency = secondaryCurrency, rateUsed = rateUsed, reason = reason,
)

fun LegacyRowDto.toEntity(): LegacyUnreadableRowEntity = LegacyUnreadableRowEntity(
    id = id, type = type, date = date, note = note,
    primaryAccountId = primaryAccountId, primaryAmount = primaryAmount, primaryCurrency = primaryCurrency,
    categoryId = categoryId, subCategoryId = subCategoryId,
    secondaryAccountId = secondaryAccountId, secondaryAmount = secondaryAmount,
    secondaryCurrency = secondaryCurrency, rateUsed = rateUsed, reason = reason,
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
    SettingsDto(
        id = id,
        baseCurrencyCode = baseCurrencyCode,
        theme = theme,
        schemaVersion = schemaVersion,
        saveTransactionRates = saveTransactionRates,
    )

fun SettingsDto.toEntity(): SettingsEntity =
    SettingsEntity(
        id = id,
        baseCurrencyCode = baseCurrencyCode,
        theme = theme,
        schemaVersion = schemaVersion,
        saveTransactionRates = saveTransactionRates,
    )
