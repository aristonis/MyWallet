package org.aristonis.mywallet.ui.reports

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.EmptyState
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.WalletListRow
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.text

/** The expand toggle's footprint; rows without one keep the same gap so every amount lines up. */
private val TOGGLE_SIZE = 48.dp

/** A sub-category sits one step in from its parent, which is what says it belongs to it. */
private val SUB_ROW_START = 32.dp
private val SUB_ROW_END = 16.dp
private val SUB_ROW_VERTICAL = 8.dp
private val SECTION_TOP = 16.dp

/** Which side of the ledger a list shows. Its prefix keeps the two sections' list keys apart. */
internal enum class CategorySection(val keyPrefix: String) { INCOME("income"), EXPENSE("expense") }

/** Which categories are open, saved as plain ids so it survives rotation and process death. */
internal val ExpandedIdsSaver = Saver<Set<Long>, LongArray>(
    save = { it.toLongArray() },
    restore = { it.toSet() },
)

/**
 * A titled by-category list inside the screen's scrolling column: the header, then each category,
 * then — for an open category — its parts directly below it. Each part is its own list item so a
 * long breakdown still scrolls lazily. [expandedIds] says which categories are open and [onToggle]
 * opens or closes one by id.
 */
internal fun LazyListScope.categorySection(
    section: CategorySection,
    @StringRes title: Int,
    @StringRes emptyText: Int,
    rows: List<CategoryRow>,
    expandedIds: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    item(key = "${section.keyPrefix}-header") {
        SectionHeader(title = stringResource(title), modifier = Modifier.padding(top = SECTION_TOP))
    }
    if (rows.isEmpty()) {
        item(key = "${section.keyPrefix}-empty") { EmptyState(message = stringResource(emptyText)) }
    }
    rows.forEach { row ->
        val isExpanded = row.canExpand && row.id in expandedIds
        item(key = "${section.keyPrefix}-${row.id}") {
            CategoryLine(row = row, isExpanded = isExpanded, onToggle = { onToggle(row.id) })
        }
        if (isExpanded) {
            items(row.subCategories, key = { "${section.keyPrefix}-sub-${row.id}-${it.id ?: "none"}" }) { sub ->
                SubCategoryLine(sub)
            }
        }
    }
}

@Composable
private fun CategoryLine(row: CategoryRow, isExpanded: Boolean, onToggle: () -> Unit) {
    WalletListRow(
        headline = row.label.text(),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MoneyText(row.totalDisplay, style = MaterialTheme.typography.titleMedium)
                if (row.canExpand) {
                    ExpandToggle(categoryName = row.label.text(), isExpanded = isExpanded, onToggle = onToggle)
                } else {
                    Spacer(Modifier.width(TOGGLE_SIZE))
                }
            }
        },
    )
}

/**
 * Opens a category onto its parts. The description names the category, because a list of identical
 * "expand" buttons tells a screen reader user nothing about which one they are on, and the state is
 * announced so they know whether tapping will open or close it.
 */
@Composable
private fun ExpandToggle(categoryName: String, isExpanded: Boolean, onToggle: () -> Unit) {
    val description = stringResource(R.string.cd_sub_categories_of, categoryName)
    val state = stringResource(if (isExpanded) R.string.state_expanded else R.string.state_collapsed)
    IconButton(
        onClick = onToggle,
        modifier = Modifier.semantics {
            contentDescription = description
            stateDescription = state
        },
    ) {
        Icon(imageVector = if (isExpanded) WalletIcons.Collapse else WalletIcons.Expand, contentDescription = null)
    }
}

/** A part of a category: quieter than its parent, and indented under it. */
@Composable
private fun SubCategoryLine(sub: SubCategoryRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SUB_ROW_START, end = SUB_ROW_END, top = SUB_ROW_VERTICAL, bottom = SUB_ROW_VERTICAL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = sub.label.text(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        MoneyText(
            sub.totalDisplay,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(TOGGLE_SIZE))
    }
}
