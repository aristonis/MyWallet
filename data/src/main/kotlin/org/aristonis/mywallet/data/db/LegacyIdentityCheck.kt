package org.aristonis.mywallet.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Proves, before the old `transactions` table is dropped, that every one of its rows can be rebuilt
 * to the exact text from `entries`, `entry_legs` and `legacy_unreadable_rows`. Throws on any
 * difference; inside the upgrade that rolls everything back and the file stays at version 2.
 *
 * It is one query of seven counts, each of which must be 0:
 * 1. one header per old row;
 * 2. exactly two legs per header that is not LEGACY_UNREADABLE (and so none on those that are);
 * 3. and 4. every readable row equals the row rebuilt from its header and legs, both ways round. The
 *    rebuild reads only the columns 1.1.1 reads, and adds the MAIN leg's amount, currency and rate,
 *    so a category leg that differs from its row, or that lost or changed its rate, fails too;
 * 5. and 6. every raw copy equals its source row, both ways round;
 * 7. every LEGACY_UNREADABLE header has a raw copy.
 *
 * EXCEPT compares text byte for byte and treats two NULLs as equal, so "1.50" and "1.5" differ and a
 * missing note matches a missing note.
 *
 * Its literals are frozen with the upgrade, like the rest of MIGRATION_2_3.
 */
fun assertLegacyIdentity(db: SupportSQLiteDatabase) {
    val counts = db.query(LEGACY_IDENTITY_SQL).use { cursor ->
        check(cursor.moveToFirst()) { "the identity check returned no row" }
        IDENTITY_COUNT_NAMES.indices.map { cursor.getLong(it) }
    }
    val failed = IDENTITY_COUNT_NAMES.zip(counts).filter { (_, count) -> count != 0L }
    check(failed.isEmpty()) {
        "the upgrade could not rebuild the old rows exactly: " + failed.joinToString { (name, count) -> "$name=$count" }
    }
}

/** The names of the seven counts, in column order, for the failure message. */
private val IDENTITY_COUNT_NAMES = listOf(
    "header_count",
    "leg_count",
    "expected_not_rebuilt",
    "rebuilt_not_expected",
    "source_not_copied",
    "copy_not_source",
    "unreadable_without_copy",
)

private const val LEGACY_IDENTITY_SQL =
    "WITH from_legs AS (" +
        "SELECT e.id, e.kind, e.date, e.note, a.accountId, " +
        "CASE WHEN e.kind = 'INCOME' THEN a.amount ELSE substr(a.amount, 2) END, " +
        "a.currencyCode, m.categoryId, m.subCategoryId, d.accountId, d.amount, d.currencyCode, e.pairRate, " +
        "m.amount, m.currencyCode, m.rateToBase " +
        "FROM entries e " +
        "JOIN entry_legs a ON a.entryId = e.id AND a.role IN ('ACCOUNT', 'SOURCE') " +
        "LEFT JOIN entry_legs m ON m.entryId = e.id AND m.role = 'MAIN' " +
        "LEFT JOIN entry_legs d ON d.entryId = e.id AND d.role = 'DEST' " +
        "WHERE e.kind <> 'LEGACY_UNREADABLE'), " +
        "expected AS (" +
        "SELECT id, type, date, note, primaryAccountId, primaryAmount, primaryCurrency, " +
        "CASE WHEN type = 'TRANSFER' THEN NULL ELSE categoryId END, " +
        "CASE WHEN type = 'TRANSFER' THEN NULL ELSE subCategoryId END, " +
        "CASE WHEN type = 'TRANSFER' THEN secondaryAccountId END, " +
        "CASE WHEN type = 'TRANSFER' THEN secondaryAmount END, " +
        "CASE WHEN type = 'TRANSFER' THEN secondaryCurrency END, " +
        "CASE WHEN type = 'TRANSFER' THEN rateUsed END, " +
        "CASE WHEN type = 'TRANSFER' THEN NULL ELSE primaryAmount END, " +
        "CASE WHEN type = 'TRANSFER' THEN NULL ELSE primaryCurrency END, " +
        "CASE WHEN type = 'TRANSFER' THEN NULL " +
        "WHEN primaryCurrency = (SELECT baseCurrencyCode FROM settings WHERE id = 0) THEN '1' " +
        "ELSE (SELECT rateToBase FROM rates r WHERE r.currencyCode = primaryCurrency) END " +
        "FROM transactions WHERE id IN (SELECT id FROM entries WHERE kind <> 'LEGACY_UNREADABLE')), " +
        "raw_copy AS (" +
        "SELECT id, type, date, note, primaryAccountId, primaryAmount, primaryCurrency, categoryId, subCategoryId, " +
        "secondaryAccountId, secondaryAmount, secondaryCurrency, rateUsed FROM legacy_unreadable_rows) " +
        "SELECT " +
        "(SELECT COUNT(*) FROM entries) - (SELECT COUNT(*) FROM transactions), " +
        "(SELECT COUNT(*) FROM entry_legs) - 2 * (SELECT COUNT(*) FROM entries WHERE kind <> 'LEGACY_UNREADABLE'), " +
        "(SELECT COUNT(*) FROM (SELECT * FROM expected EXCEPT SELECT * FROM from_legs)), " +
        "(SELECT COUNT(*) FROM (SELECT * FROM from_legs EXCEPT SELECT * FROM expected)), " +
        "(SELECT COUNT(*) FROM (SELECT * FROM transactions WHERE id IN (SELECT id FROM raw_copy) " +
        "EXCEPT SELECT * FROM raw_copy)), " +
        "(SELECT COUNT(*) FROM (SELECT * FROM raw_copy EXCEPT SELECT * FROM transactions)), " +
        "(SELECT COUNT(*) FROM entries WHERE kind = 'LEGACY_UNREADABLE' AND id NOT IN (SELECT id FROM raw_copy))"
