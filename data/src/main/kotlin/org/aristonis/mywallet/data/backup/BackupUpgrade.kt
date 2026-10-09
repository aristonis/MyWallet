package org.aristonis.mywallet.data.backup

import org.aristonis.mywallet.data.db.SETTINGS_ROW_ID
import org.aristonis.mywallet.data.db.TOP_LEVEL_PARENT_ID

/**
 * Turns a format-1 backup (written by 1.0 or 1.1) into format 2.
 *
 * It must produce exactly what the schema 2 to 3 database upgrade makes of the same rows. Otherwise
 * a wallet would come out one way when the phone upgrades and another way when the same data is
 * restored from an old file. Both paths are tested against one shared list of old rows and their
 * expected results, so they cannot drift apart.
 *
 * That is why the rules here copy the upgrade's SQL rather than the domain model:
 * - the same shape check decides which rows keep a raw copy, and why;
 * - amounts are copied as text and money going out gets a leading "-" as text, never through
 *   arithmetic, so "1.50" stays "1.50";
 * - a category leg's rate is "1" for the base currency, else the backup's own saved rate, else none;
 * - the pre-made categories are added with the same insert-or-ignore rule and the same ids.
 *
 * The literals below are frozen with the upgrade. They are not read from the app's current defaults,
 * because those may change later while old files and old databases stay as they are.
 *
 * Throws IllegalArgumentException for a file the database could never have held (two rows with one
 * id, or two top-level categories one name lookup cannot tell apart).
 */
object BackupUpgrade {

    private const val INCOME = "INCOME"
    private const val EXPENSE = "EXPENSE"
    private const val TRANSFER = "TRANSFER"
    private val READABLE_TYPES = setOf(INCOME, EXPENSE, TRANSFER)

    internal const val KIND_UNREADABLE = "LEGACY_UNREADABLE"

    internal const val UNKNOWN_TYPE = "UNKNOWN_TYPE"
    internal const val MISSING_CATEGORY = "MISSING_CATEGORY"
    internal const val MISSING_DESTINATION = "MISSING_DESTINATION"
    internal const val IGNORED_COLUMNS = "IGNORED_COLUMNS"

    /** Reasons whose row has no legs and an unreadable header. */
    private val UNREADABLE_REASONS = setOf(UNKNOWN_TYPE, MISSING_CATEGORY, MISSING_DESTINATION)

    private const val ROLE_ACCOUNT = "ACCOUNT"
    private const val ROLE_SOURCE = "SOURCE"
    private const val ROLE_MAIN = "MAIN"
    private const val ROLE_DEST = "DEST"

    /** Every leg the upgrade writes for an old row sits at the first position of its role. */
    private const val FIRST_POSITION = 0
    private const val BASE_RATE = "1"
    private const val NEGATIVE_PREFIX = "-"

    /** One pre-made category the upgrade tries to add, with the role that points at it, if any. */
    private data class PreMade(val name: String, val kind: String, val role: String?)

    /** In the upgrade's insert order; the order decides which ids the new rows get. */
    private val PRE_MADE = listOf(
        PreMade("Interest", INCOME, "INTEREST_IN"),
        PreMade("Interest", EXPENSE, "INTEREST_OUT"),
        PreMade("Tax", EXPENSE, null),
        PreMade("Fees", EXPENSE, "FEES"),
        PreMade("Lost debt", EXPENSE, "LOST_DEBT"),
        PreMade("Debt forgiven", INCOME, "DEBT_FORGIVEN"),
    )

    /** The upgrade fills the roles in this order. */
    private val ROLE_ORDER = listOf("INTEREST_IN", "INTEREST_OUT", "FEES", "LOST_DEBT", "DEBT_FORGIVEN")

    fun v1ToV2(v1: WalletBackup): WalletBackupV2 {
        val rows = v1.transactions.sortedBy { it.id }
        requireUniqueIds(rows)
        // The upgrade reads the base from the single settings row and each rate with a scalar
        // subquery, which takes the first match; a missing settings row means no base at all.
        val baseCurrencyCode = v1.settings?.takeIf { it.id == SETTINGS_ROW_ID }?.baseCurrencyCode
        val rateByCode = v1.rates.distinctBy { it.currencyCode }.associate { it.currencyCode to it.rateToBase }

        val reasonById = rows.mapNotNull { row -> legacyReason(row)?.let { row.id to it } }.toMap()
        val entries = rows.map { toEntry(it, reasonById[it.id]) }
        val kindById = entries.associate { it.id to it.kind }
        val legs = rows
            .flatMap { v2RowToLegs(it, kindById.getValue(it.id), baseCurrencyCode, rateByCode) }
            .sortedBy { insertStep(it.role) }
        val categories = addPreMadeCategories(v1.categories)

        return WalletBackupV2(
            version = BACKUP_FORMAT_V2,
            settings = v1.settings,
            accounts = v1.accounts.map { it.toV2() },
            categories = categories,
            currencies = v1.currencies,
            rates = v1.rates,
            entries = entries,
            legs = legs,
            debts = emptyList(),
            deductionItems = emptyList(),
            deductionAutoCategories = emptyList(),
            deductionLines = emptyList(),
            categoryRoles = categoryRoles(categories),
            legacyRows = rows.mapNotNull { row -> reasonById[row.id]?.let { row.toLegacyRow(it) } },
        )
    }

    /**
     * Why an old row keeps a raw copy, or null when it has exactly the shape 1.1.1 writes: an income
     * or expense with a category and no secondary columns, or a transfer with all four secondary
     * columns and no category columns. The checks run in the same order as the upgrade's CASE, so a
     * row with two faults gets the same reason in both paths.
     */
    internal fun legacyReason(row: TransactionDto): String? {
        if (row.type !in READABLE_TYPES) return UNKNOWN_TYPE
        if (row.type != TRANSFER) {
            return when {
                row.categoryId == null -> MISSING_CATEGORY
                hasAnySecondaryColumn(row) -> IGNORED_COLUMNS
                else -> null
            }
        }
        return when {
            isMissingDestination(row) -> MISSING_DESTINATION
            row.categoryId != null || row.subCategoryId != null -> IGNORED_COLUMNS
            else -> null
        }
    }

    /**
     * The legs the upgrade writes for one old row whose header kind is [kind]. An unreadable header
     * gets none. Every other row reads only the columns 1.1.1 reads, so a stray column kept in the
     * raw copy never reaches a leg.
     */
    internal fun v2RowToLegs(
        row: TransactionDto,
        kind: String,
        baseCurrencyCode: String?,
        rateByCode: Map<String, String>,
    ): List<LegDto> {
        if (kind == KIND_UNREADABLE) return emptyList()
        val accountLeg = leg(
            row.id,
            role = if (kind == TRANSFER) ROLE_SOURCE else ROLE_ACCOUNT,
            accountId = row.primaryAccountId,
            amount = if (kind == INCOME) row.primaryAmount else NEGATIVE_PREFIX + row.primaryAmount,
            currencyCode = row.primaryCurrency,
        )
        if (kind == TRANSFER) {
            val destination = leg(
                row.id,
                role = ROLE_DEST,
                accountId = row.secondaryAccountId,
                // A transfer header is only written when all four secondary columns are present.
                amount = requireNotNull(row.secondaryAmount) { "transfer ${row.id} has no destination amount" },
                currencyCode = requireNotNull(row.secondaryCurrency) { "transfer ${row.id} has no destination currency" },
            )
            return listOf(accountLeg, destination)
        }
        val mainLeg = leg(
            row.id,
            role = ROLE_MAIN,
            categoryId = row.categoryId,
            subCategoryId = row.subCategoryId,
            amount = row.primaryAmount,
            currencyCode = row.primaryCurrency,
            rateToBase = if (row.primaryCurrency == baseCurrencyCode) BASE_RATE else rateByCode[row.primaryCurrency],
        )
        return listOf(accountLeg, mainLeg)
    }

    private fun toEntry(row: TransactionDto, reason: String?): EntryDto = EntryDto(
        id = row.id,
        kind = if (reason in UNREADABLE_REASONS) KIND_UNREADABLE else row.type,
        date = row.date,
        note = row.note,
        pairRate = if (row.type == TRANSFER && reason != MISSING_DESTINATION) row.rateUsed else null,
    )

    private fun hasAnySecondaryColumn(row: TransactionDto): Boolean =
        row.secondaryAccountId != null || row.secondaryAmount != null ||
            row.secondaryCurrency != null || row.rateUsed != null

    private fun isMissingDestination(row: TransactionDto): Boolean =
        row.secondaryAccountId == null || row.secondaryAmount == null ||
            row.secondaryCurrency == null || row.rateUsed == null

    /**
     * The upgrade writes every account and source leg first, then every category leg, then every
     * destination leg, each in id order. Keeping that order means a restore numbers the legs the
     * way the upgrade did.
     */
    private fun insertStep(role: String): Int = when (role) {
        ROLE_ACCOUNT, ROLE_SOURCE -> 0
        ROLE_MAIN -> 1
        else -> 2
    }

    private fun leg(
        entryId: Long,
        role: String,
        amount: String,
        currencyCode: String,
        accountId: Long? = null,
        categoryId: Long? = null,
        subCategoryId: Long? = null,
        rateToBase: String? = null,
    ): LegDto = LegDto(
        entryId = entryId,
        role = role,
        position = FIRST_POSITION,
        accountId = accountId,
        debtId = null,
        categoryId = categoryId,
        subCategoryId = subCategoryId,
        amount = amount,
        currencyCode = currencyCode,
        rateToBase = rateToBase,
    )

    /**
     * Adds the pre-made categories the way the upgrade's single INSERT OR IGNORE does.
     *
     * A pre-made row is skipped when a top-level category of the same kind already has the same name
     * under SQLite's NOCASE rule. Ids come from AUTOINCREMENT: each attempted row takes the next id
     * after the highest one so far, and a skipped row still uses its id up. A backup does not carry
     * the categories sequence, but a restore inserts every category with its own id, which leaves the
     * sequence at the highest id, so that is where counting starts.
     */
    private fun addPreMadeCategories(existing: List<CategoryDto>): List<CategoryDto> {
        var lastId = existing.maxOfOrNull { it.id } ?: 0L
        val added = mutableListOf<CategoryDto>()
        PRE_MADE.forEach { preMade ->
            lastId += 1
            if (findTopLevel(existing + added, preMade.kind, preMade.name).isEmpty()) {
                // The backup file writes "no parent" as null, as every exported category does.
                added += CategoryDto(id = lastId, name = preMade.name, kind = preMade.kind, parentId = null)
            }
        }
        return existing + added
    }

    /** Fills each role from the one top-level category of its kind and name, as the upgrade does. */
    private fun categoryRoles(categories: List<CategoryDto>): List<CategoryRoleDto> {
        val byRole = PRE_MADE.filter { it.role != null }.associateBy { requireNotNull(it.role) }
        return ROLE_ORDER.map { role ->
            val preMade = byRole.getValue(role)
            val match = findTopLevel(categories, preMade.kind, preMade.name)
            require(match.size == 1) {
                "expected one top-level ${preMade.kind} category named ${preMade.name}, found ${match.size}"
            }
            CategoryRoleDto(role = role, categoryId = match.single().id)
        }
    }

    /** A backup file writes "no parent" as null; a stored row uses the 0 sentinel. Both are top level. */
    private fun findTopLevel(categories: List<CategoryDto>, kind: String, name: String): List<CategoryDto> =
        categories.filter {
            it.kind == kind && (it.parentId == null || it.parentId == TOP_LEVEL_PARENT_ID) &&
                sqliteNoCaseEquals(it.name, name)
        }

    /**
     * SQLite's NOCASE folds only ASCII A-Z. Kotlin's ignoreCase folds far more (it would match "ſ"
     * with "s"), so it is not used here: a name the database tells apart must stay apart here too.
     */
    internal fun sqliteNoCaseEquals(a: String, b: String): Boolean =
        a.length == b.length && a.indices.all { asciiLower(a[it]) == asciiLower(b[it]) }

    private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c

    private fun requireUniqueIds(rows: List<TransactionDto>) {
        val duplicate = rows.zipWithNext().firstOrNull { (a, b) -> a.id == b.id }
        require(duplicate == null) { "backup has two transactions with id ${duplicate?.first?.id}" }
    }

    private fun AccountDto.toV2(): AccountDtoV2 = AccountDtoV2(
        id = id,
        name = name,
        typeKey = typeKey,
        currencyCode = currencyCode,
        openingBalanceAmount = openingBalanceAmount,
        archived = archived,
        sortOrder = sortOrder,
        creditLimit = null,
        defaultFeeMode = null,
        defaultFeeValue = null,
    )

    private fun TransactionDto.toLegacyRow(reason: String): LegacyRowDto = LegacyRowDto(
        id = id,
        type = type,
        date = date,
        note = note,
        primaryAccountId = primaryAccountId,
        primaryAmount = primaryAmount,
        primaryCurrency = primaryCurrency,
        categoryId = categoryId,
        subCategoryId = subCategoryId,
        secondaryAccountId = secondaryAccountId,
        secondaryAmount = secondaryAmount,
        secondaryCurrency = secondaryCurrency,
        rateUsed = rateUsed,
        reason = reason,
    )
}
