package org.aristonis.mywallet.data.backup

import java.time.LocalDate

/**
 * Small, valid backup files for the restore tests, built from plain values so the JVM checks and the
 * on-device restore tests start from the same wallet.
 *
 * [wallet] is a format-2 file holding the three kinds 1.1.1 knew, the pre-made categories and their
 * roles. [versionOneText] is the same wallet as 1.1.1 wrote it (format 1, JSON text), so a test can
 * hand the converter a file shaped exactly like one from the field.
 */
object BackupV2Samples {

    const val SALARY = 10L
    const val FOOD = 11L
    const val GROCERIES = 21L
    const val INTEREST_INCOME = 30L
    const val INTEREST_EXPENSE = 31L
    const val FEES = 32L
    const val LOST_DEBT = 33L
    const val DEBT_FORGIVEN = 34L
    const val TAX = 35L

    const val CASH = 1L
    const val BANK = 2L

    const val INCOME_ENTRY = 1L
    const val EXPENSE_ENTRY = 2L
    const val TRANSFER_ENTRY = 3L

    private val JAN_5 = LocalDate.of(2026, 1, 5)
    private val JAN_6 = LocalDate.of(2026, 1, 6)
    private val JAN_7 = LocalDate.of(2026, 1, 7)

    fun wallet(): WalletBackupV2 = WalletBackupV2(
        version = BACKUP_FORMAT_V2,
        settings = SettingsDto(baseCurrencyCode = "USD", theme = "SYSTEM", schemaVersion = 4),
        accounts = listOf(
            AccountDtoV2(id = CASH, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalanceAmount = "100.50"),
            AccountDtoV2(id = BANK, name = "Bank", typeKey = "card", currencyCode = "EUR", openingBalanceAmount = "0.00"),
        ),
        categories = listOf(
            CategoryDto(id = SALARY, name = "Salary", kind = "INCOME"),
            CategoryDto(id = FOOD, name = "Food", kind = "EXPENSE"),
            CategoryDto(id = GROCERIES, name = "Groceries", kind = "EXPENSE", parentId = FOOD),
            CategoryDto(id = INTEREST_INCOME, name = "Interest", kind = "INCOME"),
            CategoryDto(id = INTEREST_EXPENSE, name = "Interest", kind = "EXPENSE"),
            CategoryDto(id = FEES, name = "Fees", kind = "EXPENSE"),
            CategoryDto(id = LOST_DEBT, name = "Lost debt", kind = "EXPENSE"),
            CategoryDto(id = DEBT_FORGIVEN, name = "Debt forgiven", kind = "INCOME"),
            CategoryDto(id = TAX, name = "Tax", kind = "EXPENSE"),
        ),
        currencies = listOf(
            CurrencyDto(code = "USD", symbol = "$", decimalPlaces = 2),
            CurrencyDto(code = "EUR", symbol = "€", decimalPlaces = 2),
        ),
        rates = listOf(RateDto(currencyCode = "EUR", rateToBase = "1.086956")),
        entries = listOf(
            EntryDto(INCOME_ENTRY, "INCOME", JAN_5, note = null, pairRate = null),
            EntryDto(EXPENSE_ENTRY, "EXPENSE", JAN_6, note = "lunch", pairRate = null),
            EntryDto(TRANSFER_ENTRY, "TRANSFER", JAN_7, note = null, pairRate = "0.920000"),
        ),
        legs = listOf(
            account(INCOME_ENTRY, "ACCOUNT", CASH, "1234.00", "USD"),
            main(INCOME_ENTRY, SALARY, null, "1234.00", "USD", rate = "1"),
            account(EXPENSE_ENTRY, "ACCOUNT", CASH, "-12.99", "USD"),
            main(EXPENSE_ENTRY, FOOD, GROCERIES, "12.99", "USD", rate = "1"),
            account(TRANSFER_ENTRY, "SOURCE", CASH, "-20.000", "USD"),
            account(TRANSFER_ENTRY, "DEST", BANK, "18.40", "EUR"),
        ),
        debts = emptyList(),
        deductionItems = emptyList(),
        deductionAutoCategories = emptyList(),
        deductionLines = emptyList(),
        categoryRoles = listOf(
            CategoryRoleDto("INTEREST_IN", INTEREST_INCOME),
            CategoryRoleDto("INTEREST_OUT", INTEREST_EXPENSE),
            CategoryRoleDto("FEES", FEES),
            CategoryRoleDto("LOST_DEBT", LOST_DEBT),
            CategoryRoleDto("DEBT_FORGIVEN", DEBT_FORGIVEN),
        ),
        legacyRows = emptyList(),
    )

    fun account(entryId: Long, role: String, accountId: Long, amount: String, currency: String) = LegDto(
        entryId = entryId, role = role, position = 0, accountId = accountId, debtId = null,
        categoryId = null, subCategoryId = null, amount = amount, currencyCode = currency, rateToBase = null,
    )

    fun main(entryId: Long, categoryId: Long, subCategoryId: Long?, amount: String, currency: String, rate: String?) = LegDto(
        entryId = entryId, role = "MAIN", position = 0, accountId = null, debtId = null,
        categoryId = categoryId, subCategoryId = subCategoryId, amount = amount, currencyCode = currency, rateToBase = rate,
    )

    /** A header 1.1.1 could not have read: no recipe reads its kind, and it has no legs. */
    fun unreadableEntry(id: Long) =
        EntryDto(id, "LEGACY_UNREADABLE", LocalDate.of(2026, 2, 1), note = "odd row", pairRate = null)

    /** The raw copy of an old row, as the upgrade keeps it. */
    fun rawCopy(id: Long, reason: String = "UNKNOWN_TYPE", type: String = "REFUND") = LegacyRowDto(
        id = id, type = type, date = LocalDate.of(2026, 2, 1), note = "odd row",
        primaryAccountId = CASH, primaryAmount = "10.00", primaryCurrency = "USD",
        categoryId = FOOD, subCategoryId = null, secondaryAccountId = null, secondaryAmount = null,
        secondaryCurrency = null, rateUsed = null, reason = reason,
    )

    /** [wallet] plus one unreadable entry and its raw copy, both with [id]. */
    fun walletWithUnreadableRow(id: Long = 4, reason: String = "UNKNOWN_TYPE"): WalletBackupV2 = wallet().let {
        it.copy(entries = it.entries + unreadableEntry(id), legacyRows = listOf(rawCopy(id, reason)))
    }

    /** One old-format transaction object as 1.1.1 wrote it, as JSON text. */
    fun versionOneTransaction(
        id: Long,
        type: String,
        date: String,
        primaryAccountId: Long,
        primaryAmount: String,
        primaryCurrency: String,
        categoryId: Long? = null,
        subCategoryId: Long? = null,
        secondaryAccountId: Long? = null,
        secondaryAmount: String? = null,
        secondaryCurrency: String? = null,
        rateUsed: String? = null,
        note: String? = null,
    ): String = """{"id":$id,"type":${text(type)},"date":${text(date)},"note":${text(note)},""" +
        """"primaryAccountId":$primaryAccountId,"primaryAmount":${text(primaryAmount)},""" +
        """"primaryCurrency":${text(primaryCurrency)},"categoryId":$categoryId,"subCategoryId":$subCategoryId,""" +
        """"secondaryAccountId":$secondaryAccountId,"secondaryAmount":${text(secondaryAmount)},""" +
        """"secondaryCurrency":${text(secondaryCurrency)},"rateUsed":${text(rateUsed)}}"""

    /** The three sample kinds as old-format transactions: the same wallet as [wallet], before the upgrade. */
    fun versionOneTransactions(): List<String> = listOf(
        versionOneTransaction(1, "INCOME", "2026-01-05", CASH, "1234.00", "USD", categoryId = SALARY),
        versionOneTransaction(
            2, "EXPENSE", "2026-01-06", CASH, "12.99", "USD", categoryId = FOOD, subCategoryId = GROCERIES, note = "lunch",
        ),
        versionOneTransaction(
            3, "TRANSFER", "2026-01-07", CASH, "20.000", "USD",
            secondaryAccountId = BANK, secondaryAmount = "18.40", secondaryCurrency = "EUR", rateUsed = "0.920000",
        ),
    )

    /**
     * A format-1 backup file as text. It carries only the user's own categories (the pre-made ones
     * come from the upgrade), and [transactions] are the already-built transaction objects.
     */
    fun versionOneText(transactions: List<String> = versionOneTransactions()): String =
        """{"version":1,"settings":{"id":0,"baseCurrencyCode":"USD","theme":"SYSTEM","schemaVersion":4},""" +
            """"accounts":[""" +
            """{"id":1,"name":"Cash","typeKey":"cash","currencyCode":"USD","openingBalanceAmount":"100.50","archived":false,"sortOrder":0},""" +
            """{"id":2,"name":"Bank","typeKey":"card","currencyCode":"EUR","openingBalanceAmount":"0.00","archived":false,"sortOrder":1}],""" +
            """"transactions":[${transactions.joinToString(",")}],""" +
            """"categories":[""" +
            """{"id":10,"name":"Salary","kind":"INCOME","parentId":null,"systemKey":null},""" +
            """{"id":11,"name":"Food","kind":"EXPENSE","parentId":null,"systemKey":null},""" +
            """{"id":21,"name":"Groceries","kind":"EXPENSE","parentId":11,"systemKey":null}],""" +
            """"currencies":[{"code":"USD","symbol":"$","decimalPlaces":2},{"code":"EUR","symbol":"€","decimalPlaces":2}],""" +
            """"rates":[{"currencyCode":"EUR","rateToBase":"1.086956"}]}"""

    private fun text(value: String?): String = if (value == null) "null" else "\"$value\""
}
