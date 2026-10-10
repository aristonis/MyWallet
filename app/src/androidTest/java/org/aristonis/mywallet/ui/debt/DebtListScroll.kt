package org.aristonis.mywallet.ui.debt

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollToNode

/** Scrolls the list that holds a node containing [text] into view; fails when no list holds one. */
internal fun ComposeContentTestRule.reachDebtRow(text: String) {
    val lists = onAllNodes(hasScrollToNodeAction())
    lists.fetchSemanticsNodes().indices.forEach { index ->
        try {
            lists[index].performScrollToNode(hasText(text, substring = true))
            return
        } catch (_: AssertionError) {
            // This list does not hold the row; try the next one.
        }
    }
    error("no scrollable debt list reached $text")
}
