package org.aristonis.mywallet.ui

import org.aristonis.mywallet.domain.model.Category

/**
 * The single place that turns a category into text a person reads.
 *
 * A user category shows its own name. An app-owned bucket does not: its stored name is the system
 * key, chosen precisely because nobody would type it, so a screen reading `.name` prints
 * `UNCATEGORIZED_EXPENSE` at the user. Every reader goes through here instead, which is also what
 * makes the bucket translatable later without touching a single stored row.
 */
fun Category.label(): String = displayName(::systemCategoryLabel)

/** The label for an app-owned bucket, keyed by its system key rather than by its stored name. */
fun systemCategoryLabel(@Suppress("UNUSED_PARAMETER") systemKey: String): String = "Uncategorized"
