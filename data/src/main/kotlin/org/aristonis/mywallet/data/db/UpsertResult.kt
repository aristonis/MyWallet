package org.aristonis.mywallet.data.db

/**
 * What Room's `@Upsert` returns instead of a row id when it fell through to its UPDATE branch.
 *
 * The trap is which conflicts send it there. Room tries the INSERT, catches any uniqueness failure
 * and retries as an UPDATE — so this shows up for a clash on ANY unique index, not just the primary
 * key. That makes the return value mean two opposite things depending on the row:
 *
 * - the row already had an id, so the UPDATE matched it and the id it kept is simply its own;
 * - the row had no id yet, so the UPDATE had nothing to match and NOTHING was written.
 *
 * Only the first case has a row id to report. In the second the value is not an id at all, and
 * passing it on would put -1 into a column that is supposed to point at a real row. Every caller
 * has to separate the two before using the result.
 */
const val UPSERT_NO_ROW_ID: Long = -1L
