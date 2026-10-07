package org.aristonis.mywallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Category

/**
 * What to call a category, decided without wording it.
 *
 * A user category shows its own name. An app-owned bucket does not: its stored name is the system
 * key, chosen precisely because nobody would type it, so a screen reading `.name` prints
 * `UNCATEGORIZED_EXPENSE` at the user. View-models resolve the *shape* here and screens resolve the
 * *text*, which is what lets the bucket be translated without a `Context` reaching a view-model.
 */
sealed interface CategoryLabel {
    /** A category the user named. Its name is already the finished text. */
    data class Named(val name: String) : CategoryLabel

    /** An app-owned bucket, identified by its reserved key rather than by any stored text. */
    data class SystemBucket(val systemKey: String) : CategoryLabel

    /** A category id that resolved to nothing — a deleted row still referenced somewhere. */
    data object Unknown : CategoryLabel
}

fun Category.label(): CategoryLabel =
    systemKey?.let(CategoryLabel::SystemBucket) ?: CategoryLabel.Named(name)

/** The text for a label. Both buckets read the same today; the key is what keeps them separable. */
@Composable
fun CategoryLabel.text(): String = when (this) {
    is CategoryLabel.Named -> name
    is CategoryLabel.SystemBucket -> stringResource(R.string.category_uncategorized)
    CategoryLabel.Unknown -> stringResource(R.string.value_missing)
}
