package org.aristonis.mywallet.data.fixture

/**
 * A small schema-2 wallet (the 1.1.1 database shape) and what each of its `transactions` rows must
 * become in schema 3.
 *
 * The database upgrade test and the old-backup converter test are both checked against these same
 * rows, so the two paths cannot drift apart: a row that upgrades one way on the phone must convert
 * the same way from an old backup file.
 *
 * Everything here is plain strings, numbers, lists and maps on purpose. The fixture describes data
 * that is already frozen on people's phones, so it must not change shape when the app's domain or
 * Room types change.
 *
 * Amounts are text and keep their exact characters ("1.50" is not "1.5"), because the upgrade
 * copies them byte for byte.
 */
object LegacyFixture {

    /** One `transactions` row, column for column as schema 2 stores it. [date] is an epoch day. */
    data class Row(
        val id: Long,
        val type: String,
        val date: Long,
        val note: String?,
        val primaryAccountId: Long,
        val primaryAmount: String,
        val primaryCurrency: String,
        val categoryId: Long? = null,
        val subCategoryId: Long? = null,
        val secondaryAccountId: Long? = null,
        val secondaryAmount: String? = null,
        val secondaryCurrency: String? = null,
        val rateUsed: String? = null,
    )

    /** One schema-3 `entry_legs` row the upgrade must produce for a [Row]. */
    data class ExpectedLeg(
        val role: String,
        val position: Int,
        val accountId: Long?,
        val categoryId: Long?,
        val subCategoryId: Long?,
        val amount: String,
        val currencyCode: String,
        val rateToBase: String?,
    )

    /**
     * A row and its expected result. [reason] is null for a row 1.1.1 reads cleanly; otherwise it
     * is the reason the raw copy of the row is kept. [expectedPairRate] is the entry header's
     * `pairRate`: the row's `rateUsed` for a transfer that migrates, null for everything else.
     */
    data class Case(
        val row: Row,
        val expectedKind: String,
        val expectedLegs: List<ExpectedLeg>,
        val reason: String?,
        val expectedPairRate: String?,
    )

    /** One schema-2 `accounts` row. */
    data class AccountRow(
        val id: Long,
        val name: String,
        val typeKey: String,
        val currencyCode: String,
        val openingBalanceAmount: String,
        val archived: Boolean,
        val sortOrder: Int,
    )

    /** One schema-2 `categories` row. A [parentId] of 0 means top level. */
    data class CategoryRow(
        val id: Long,
        val name: String,
        val kind: String,
        val parentId: Long,
        val systemKey: String? = null,
    )

    /** Schema-2 `transactions.type` values, which are also the schema-3 entry kinds they keep. */
    const val INCOME = "INCOME"
    const val EXPENSE = "EXPENSE"
    const val TRANSFER = "TRANSFER"

    /** The entry kind of a row that cannot be read: it gets a header and a raw copy, no legs. */
    const val KIND_UNREADABLE = "LEGACY_UNREADABLE"

    const val UNKNOWN_TYPE = "UNKNOWN_TYPE"
    const val MISSING_CATEGORY = "MISSING_CATEGORY"
    const val MISSING_DESTINATION = "MISSING_DESTINATION"
    const val IGNORED_COLUMNS = "IGNORED_COLUMNS"

    /** Every reason a raw copy can carry. */
    val REASONS: List<String> = listOf(UNKNOWN_TYPE, MISSING_CATEGORY, MISSING_DESTINATION, IGNORED_COLUMNS)

    private const val USD = "USD"
    private const val EUR = "EUR"
    private const val AED = "AED"
    private const val JPY = "JPY"

    private const val RATE_ONE = "1"

    /** A saved rate for the base currency itself; the upgrade must still store "1" for it. */
    private const val USD_SAVED_RATE = "1.0"
    private const val EUR_RATE = "1.08"
    private const val AED_RATE = "0.2723"

    /** Transfer rates the user typed in 1.1.1; they become the entry's pair rate. */
    private const val CASH_TO_EURO_RATE = "0.9259"
    private const val BANK_TO_CARD_RATE = "3.6724"

    /** Epoch day of 2026-01-01; row dates count from here so they read as calendar days. */
    private const val JAN_1 = 20454L
    private const val FEB_1 = JAN_1 + 31
    private const val MAR_1 = FEB_1 + 28

    private const val CASH = 1L
    private const val EURO_SAVINGS = 2L
    private const val DIRHAM_CARD = 3L
    private const val OLD_WALLET = 4L
    private const val MAIN_BANK = 5L
    private const val TOKYO_CASH = 6L

    private const val SALARY = 1L
    private const val GIFTS = 2L
    private const val FOOD = 3L
    private const val GROCERIES = 4L
    private const val RESTAURANTS = 5L
    private const val TAX = 6L
    private const val FEES = 7L
    private const val TRANSPORT = 8L

    /** `settings.baseCurrencyCode`. */
    val baseCurrencyCode: String = USD

    /**
     * `rates`: currency code to `rateToBase` text. JPY is used but has no saved rate. USD is the base
     * and also has a saved row, so a base-currency row proves the "1" rule wins over the table.
     */
    val rates: Map<String, String> = mapOf(USD to USD_SAVED_RATE, EUR to EUR_RATE, AED to AED_RATE)

    val accounts: List<AccountRow> = listOf(
        AccountRow(CASH, "Cash", "cash", USD, "0", archived = false, sortOrder = 0),
        AccountRow(EURO_SAVINGS, "Euro Savings", "savings", EUR, "250.00", archived = false, sortOrder = 1),
        AccountRow(DIRHAM_CARD, "Dirham Card", "card", AED, "1300000", archived = false, sortOrder = 2),
        AccountRow(OLD_WALLET, "Old Wallet", "cash", USD, "12.345", archived = true, sortOrder = 3),
        AccountRow(MAIN_BANK, "Main Bank", "bank", USD, "1500.5", archived = false, sortOrder = 4),
        AccountRow(TOKYO_CASH, "Tokyo Cash", "cash", JPY, "0", archived = false, sortOrder = 5),
    )

    /**
     * "Tax" and lowercase "fees" are top-level expenses the upgrade must reuse instead of adding
     * its own; "Food" has children so a row on it with no sub-category is meaningful.
     */
    val categories: List<CategoryRow> = listOf(
        CategoryRow(SALARY, "Salary", INCOME, parentId = 0),
        CategoryRow(GIFTS, "Gifts", INCOME, parentId = 0),
        CategoryRow(FOOD, "Food", EXPENSE, parentId = 0),
        CategoryRow(GROCERIES, "Groceries", EXPENSE, parentId = FOOD),
        CategoryRow(RESTAURANTS, "Restaurants", EXPENSE, parentId = FOOD),
        CategoryRow(TAX, "Tax", EXPENSE, parentId = 0),
        CategoryRow(FEES, "fees", EXPENSE, parentId = 0),
        CategoryRow(TRANSPORT, "Transport", EXPENSE, parentId = 0),
        CategoryRow(9, "Uncategorized", EXPENSE, parentId = 0, systemKey = "UNCATEGORIZED_EXPENSE"),
    )

    val cases: List<Case> = listOf(
        // Base-currency income: the category leg stores rate "1".
        Case(
            Row(1, INCOME, JAN_1, "January salary", CASH, "2500.00", USD, categoryId = SALARY),
            INCOME,
            listOf(account(CASH, "2500.00", USD), main(SALARY, null, "2500.00", USD, RATE_ONE)),
            reason = null,
            expectedPairRate = null,
        ),
        // Base-currency expense with a sub-category and an empty (not null) note.
        Case(
            Row(2, EXPENSE, JAN_1 + 2, "", CASH, "45.5", USD, categoryId = FOOD, subCategoryId = GROCERIES),
            EXPENSE,
            listOf(account(CASH, "-45.5", USD), main(FOOD, GROCERIES, "45.5", USD, RATE_ONE)),
            reason = null,
            expectedPairRate = null,
        ),
        // Rated expense on a parent that has children, sub-category cleared, null note.
        Case(
            Row(3, EXPENSE, JAN_1 + 4, null, EURO_SAVINGS, "1.50", EUR, categoryId = FOOD),
            EXPENSE,
            listOf(account(EURO_SAVINGS, "-1.50", EUR), main(FOOD, null, "1.50", EUR, EUR_RATE)),
            reason = null,
            expectedPairRate = null,
        ),
        // Rated income with a large whole amount.
        Case(
            Row(4, INCOME, JAN_1 + 9, "Bonus", DIRHAM_CARD, "1300000", AED, categoryId = GIFTS),
            INCOME,
            listOf(account(DIRHAM_CARD, "1300000", AED), main(GIFTS, null, "1300000", AED, AED_RATE)),
            reason = null,
            expectedPairRate = null,
        ),
        // Expense on the user's own "Tax", three decimals.
        Case(
            Row(5, EXPENSE, JAN_1 + 14, "Quarterly tax", CASH, "12.345", USD, categoryId = TAX),
            EXPENSE,
            listOf(account(CASH, "-12.345", USD), main(TAX, null, "12.345", USD, RATE_ONE)),
            reason = null,
            expectedPairRate = null,
        ),
        // Expense on the user's own lowercase "fees", rated.
        Case(
            Row(6, EXPENSE, JAN_1 + 20, "Card fee", EURO_SAVINGS, "0.99", EUR, categoryId = FEES),
            EXPENSE,
            listOf(account(EURO_SAVINGS, "-0.99", EUR), main(FEES, null, "0.99", EUR, EUR_RATE)),
            reason = null,
            expectedPairRate = null,
        ),
        // Non-base currency with no saved rate: the category leg stores no rate.
        Case(
            Row(7, EXPENSE, FEB_1, "Train", TOKYO_CASH, "1500", JPY, categoryId = TRANSPORT),
            EXPENSE,
            listOf(account(TOKYO_CASH, "-1500", JPY), main(TRANSPORT, null, "1500", JPY, null)),
            reason = null,
            expectedPairRate = null,
        ),
        // Cross-currency transfer.
        Case(
            Row(
                8, TRANSFER, FEB_1 + 3, "To savings", CASH, "100.00", USD,
                secondaryAccountId = EURO_SAVINGS, secondaryAmount = "92.59", secondaryCurrency = EUR,
                rateUsed = CASH_TO_EURO_RATE,
            ),
            TRANSFER,
            listOf(source(CASH, "-100.00", USD), dest(EURO_SAVINGS, "92.59", EUR)),
            reason = null,
            expectedPairRate = CASH_TO_EURO_RATE,
        ),
        // Same-currency transfer.
        Case(
            Row(
                9, TRANSFER, FEB_1 + 10, null, CASH, "300", USD,
                secondaryAccountId = MAIN_BANK, secondaryAmount = "300", secondaryCurrency = USD,
                rateUsed = RATE_ONE,
            ),
            TRANSFER,
            listOf(source(CASH, "-300", USD), dest(MAIN_BANK, "300", USD)),
            reason = null,
            expectedPairRate = RATE_ONE,
        ),
        // Ids 10 and 11 were deleted, so the ids have a gap.
        // Income on an archived account.
        Case(
            Row(12, INCOME, FEB_1 + 15, "Refund from friend", OLD_WALLET, "75.25", USD, categoryId = GIFTS),
            INCOME,
            listOf(account(OLD_WALLET, "75.25", USD), main(GIFTS, null, "75.25", USD, RATE_ONE)),
            reason = null,
            expectedPairRate = null,
        ),
        // Expense with a sub-category, base currency, in a later month.
        Case(
            Row(
                13, EXPENSE, MAR_1, "Dinner", MAIN_BANK, "64.80", USD,
                categoryId = FOOD, subCategoryId = RESTAURANTS,
            ),
            EXPENSE,
            listOf(account(MAIN_BANK, "-64.80", USD), main(FOOD, RESTAURANTS, "64.80", USD, RATE_ONE)),
            reason = null,
            expectedPairRate = null,
        ),
        // A type 1.1.1 never wrote.
        Case(
            Row(14, "REFUND", MAR_1 + 2, "Odd row", CASH, "10.00", USD, categoryId = FOOD),
            KIND_UNREADABLE,
            emptyList(),
            reason = UNKNOWN_TYPE,
            expectedPairRate = null,
        ),
        // An expense with no category.
        Case(
            Row(15, EXPENSE, MAR_1 + 3, null, CASH, "8.20", USD),
            KIND_UNREADABLE,
            emptyList(),
            reason = MISSING_CATEGORY,
            expectedPairRate = null,
        ),
        // A transfer with a destination account but no amount and no rate.
        Case(
            Row(
                16, TRANSFER, MAR_1 + 4, "Half a transfer", CASH, "50", USD,
                secondaryAccountId = EURO_SAVINGS, secondaryCurrency = EUR,
            ),
            KIND_UNREADABLE,
            emptyList(),
            reason = MISSING_DESTINATION,
            expectedPairRate = null,
        ),
        // A readable expense with a stray secondary currency 1.1.1 ignores: it migrates normally and
        // its raw copy is kept as well.
        Case(
            Row(17, EXPENSE, MAR_1 + 5, "Coffee", CASH, "3.10", USD, categoryId = FOOD, secondaryCurrency = EUR),
            EXPENSE,
            listOf(account(CASH, "-3.10", USD), main(FOOD, null, "3.10", USD, RATE_ONE)),
            reason = IGNORED_COLUMNS,
            expectedPairRate = null,
        ),
        // A readable expense with a stray rateUsed 1.1.1 never read: it migrates from the columns
        // 1.1.1 reads, so its category leg takes the saved EUR rate, not the stray one.
        Case(
            Row(
                18, EXPENSE, MAR_1 + 6, "Lunch", EURO_SAVINGS, "12.00", EUR,
                categoryId = FOOD, subCategoryId = RESTAURANTS, rateUsed = "1.1",
            ),
            EXPENSE,
            listOf(account(EURO_SAVINGS, "-12.00", EUR), main(FOOD, RESTAURANTS, "12.00", EUR, EUR_RATE)),
            reason = IGNORED_COLUMNS,
            expectedPairRate = null,
        ),
        // A readable transfer with a stray category: it stays a transfer, no leg gets the category,
        // and its rate is still kept as the pair rate.
        Case(
            Row(
                19, TRANSFER, MAR_1 + 7, "Top up card", MAIN_BANK, "200", USD, categoryId = FEES,
                secondaryAccountId = DIRHAM_CARD, secondaryAmount = "734.48", secondaryCurrency = AED,
                rateUsed = BANK_TO_CARD_RATE,
            ),
            TRANSFER,
            listOf(source(MAIN_BANK, "-200", USD), dest(DIRHAM_CARD, "734.48", AED)),
            reason = IGNORED_COLUMNS,
            expectedPairRate = BANK_TO_CARD_RATE,
        ),
        // A transfer with no destination amount that also carries a category: the missing
        // destination is the reason, so the row is unreadable rather than kept with ignored columns.
        Case(
            Row(
                20, TRANSFER, MAR_1 + 8, null, CASH, "20", USD, categoryId = TRANSPORT,
                secondaryAccountId = MAIN_BANK, secondaryCurrency = USD, rateUsed = RATE_ONE,
            ),
            KIND_UNREADABLE,
            emptyList(),
            reason = MISSING_DESTINATION,
            expectedPairRate = null,
        ),
        // An income with no category.
        Case(
            Row(21, INCOME, MAR_1 + 9, "Cash gift", CASH, "40", USD),
            KIND_UNREADABLE,
            emptyList(),
            reason = MISSING_CATEGORY,
            expectedPairRate = null,
        ),
        // A rated income with a leading zero, which a number round trip could turn into ".05" or "5E-2".
        Case(
            Row(22, INCOME, MAR_1 + 10, "Interest", EURO_SAVINGS, "0.05", EUR, categoryId = GIFTS),
            INCOME,
            listOf(account(EURO_SAVINGS, "0.05", EUR), main(GIFTS, null, "0.05", EUR, EUR_RATE)),
            reason = null,
            expectedPairRate = null,
        ),
        // A note with a double quote, a line break and a non-ASCII letter: it must arrive unchanged,
        // not escaped, trimmed or re-encoded.
        Case(
            Row(
                23, EXPENSE, MAR_1 + 11, "Café \"night\"\nout", MAIN_BANK, "27.40", USD,
                categoryId = FOOD, subCategoryId = RESTAURANTS,
            ),
            EXPENSE,
            listOf(account(MAIN_BANK, "-27.40", USD), main(FOOD, RESTAURANTS, "27.40", USD, RATE_ONE)),
            reason = null,
            expectedPairRate = null,
        ),
    )

    /** `sqlite_sequence.seq` for `transactions`: above the last row, because rows 24 and 25 were deleted. */
    val sequenceBefore: Long = 25L

    /**
     * The category each role must point at after the upgrade.
     *
     * The upgrade runs one INSERT OR IGNORE of six rows, in this order: Interest (income),
     * Interest (expense), Tax, Fees, Lost debt, Debt forgiven. Before it the categories sequence is 9,
     * the highest id here. SQLite AUTOINCREMENT hands out an id to every row it tries, including one
     * the unique (kind, parentId, name NOCASE) index then ignores, so the ids go:
     *
     * - Interest (income) gets 10, Interest (expense) gets 11;
     * - Tax matches the existing "Tax" (6) and is ignored, using up 12;
     * - Fees matches the existing lowercase "fees" (7) and is ignored, using up 13;
     * - Lost debt gets 14, Debt forgiven gets 15.
     *
     * The roles then look each name up, so FEES lands on the user's own "fees".
     */
    val expectedRoles: Map<String, Long> = mapOf(
        "INTEREST_IN" to 10L,
        "INTEREST_OUT" to 11L,
        "FEES" to FEES,
        "LOST_DEBT" to 14L,
        "DEBT_FORGIVEN" to 15L,
    )

    /** The nine existing categories plus the four the upgrade adds; Tax and Fees are reused. */
    val expectedCategoryCountAfter: Int = 13

    private fun account(accountId: Long, amount: String, currency: String) =
        ExpectedLeg("ACCOUNT", 0, accountId, null, null, amount, currency, null)

    private fun main(categoryId: Long, subCategoryId: Long?, amount: String, currency: String, rate: String?) =
        ExpectedLeg("MAIN", 0, null, categoryId, subCategoryId, amount, currency, rate)

    private fun source(accountId: Long, amount: String, currency: String) =
        ExpectedLeg("SOURCE", 0, accountId, null, null, amount, currency, null)

    private fun dest(accountId: Long, amount: String, currency: String) =
        ExpectedLeg("DEST", 0, accountId, null, null, amount, currency, null)
}
