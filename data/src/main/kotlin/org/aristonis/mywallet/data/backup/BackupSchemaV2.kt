package org.aristonis.mywallet.data.backup

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Backup format 2: one list per schema-3 table, field for field.
 *
 * It sits beside format 1 ([WalletBackup]) instead of replacing it. Format 1 stays frozen because
 * files written by 1.0 and 1.1 still have to be read; [BackupUpgrade.v1ToV2] turns them into this
 * shape. As in format 1, money and rates are exact decimal text so "1.50" never becomes "1.5", and
 * dates are ISO-8601 strings.
 *
 * Settings, categories, currencies and rates did not change shape in schema 3, so their format-1
 * DTOs are reused here.
 */

/** The `version` a format-2 file carries. */
const val BACKUP_FORMAT_V2 = 2

/** Format 3 keeps the format-2 DTO fields and permits the current ledger receipts. */
const val BACKUP_FORMAT_V3 = 3

/** An `accounts` row: the format-1 fields plus the three columns schema 3 adds, all nullable. */
@Serializable
data class AccountDtoV2(
    val id: Long = 0,
    val name: String,
    val typeKey: String,
    val currencyCode: String,
    val openingBalanceAmount: String,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val creditLimit: String? = null,
    val defaultFeeMode: String? = null,
    val defaultFeeValue: String? = null,
)

/** An `entries` row: the header of one entry. Its money lives on its [LegDto]s. */
@Serializable
data class EntryDto(
    val id: Long,
    val kind: String,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val note: String?,
    val pairRate: String?,
)

/**
 * An `entry_legs` row: one signed amount of an entry on an account, a debt or a category. The leg
 * id is not stored; a restore assigns ids in list order.
 */
@Serializable
data class LegDto(
    val entryId: Long,
    val role: String,
    val position: Int,
    val accountId: Long?,
    val debtId: Long?,
    val categoryId: Long?,
    val subCategoryId: Long?,
    val amount: String,
    val currencyCode: String,
    val rateToBase: String?,
)

/** A `debts` row. Its remaining amount is never stored; it is the sum of the debt's legs. */
@Serializable
data class DebtDto(
    val id: Long,
    val counterparty: String,
    val counterpartyKey: String,
    val side: String,
    val currencyCode: String,
    @Serializable(with = LocalDateSerializer::class) val dueDate: LocalDate?,
    val note: String?,
)

/** A `deduction_items` row. [nameKey] is the trimmed, case-folded [name]. */
@Serializable
data class DeductionItemDto(
    val id: Long,
    val name: String,
    val nameKey: String,
    val mode: String,
    val value: String,
    val valueCurrencyCode: String?,
    val timing: String,
    val expenseCategoryId: Long,
    val enabled: Boolean,
    val sortOrder: Int,
)

/** A `deduction_item_auto_categories` row. */
@Serializable
data class DeductionAutoCategoryDto(
    val itemId: Long,
    val incomeCategoryId: Long,
)

/** An `income_deduction_lines` row: one deduction as it was applied to one income. */
@Serializable
data class DeductionLineDto(
    val id: Long,
    val entryId: Long,
    val itemId: Long,
    val position: Int,
    val name: String,
    val mode: String,
    val value: String,
    val valueCurrencyCode: String?,
    val timing: String,
)

/** A `category_roles` row: the category a built-in role files its legs under. */
@Serializable
data class CategoryRoleDto(
    val role: String,
    val categoryId: Long,
)

/**
 * A `legacy_unreadable_rows` row: the raw copy of an old transactions row, column for column, and
 * the [reason] it was kept. A restore writes it back verbatim so export, clear data and restore
 * never lose a row the app could not read.
 */
@Serializable
data class LegacyRowDto(
    val id: Long,
    val type: String,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val note: String?,
    val primaryAccountId: Long,
    val primaryAmount: String,
    val primaryCurrency: String,
    val categoryId: Long?,
    val subCategoryId: Long?,
    val secondaryAccountId: Long?,
    val secondaryAmount: String?,
    val secondaryCurrency: String?,
    val rateUsed: String?,
    val reason: String,
)

/**
 * A whole format-2 backup. Lists are in table order: parents before the rows that point at them.
 *
 * This format is frozen once 1.2.0 ships, and so are the format-1 DTOs it reuses. Readers reject keys
 * they do not know, so any new field means format 3 with its own converter from format 2; and
 * [version] has no default, so a file that does not say which format it is never reads as this one.
 */
@Serializable
data class WalletBackupV2(
    val version: Int,
    val settings: SettingsDto?,
    val accounts: List<AccountDtoV2>,
    val categories: List<CategoryDto>,
    val currencies: List<CurrencyDto>,
    val rates: List<RateDto>,
    val entries: List<EntryDto>,
    val legs: List<LegDto>,
    val debts: List<DebtDto>,
    val deductionItems: List<DeductionItemDto>,
    val deductionAutoCategories: List<DeductionAutoCategoryDto>,
    val deductionLines: List<DeductionLineDto>,
    val categoryRoles: List<CategoryRoleDto>,
    val legacyRows: List<LegacyRowDto>,
)
