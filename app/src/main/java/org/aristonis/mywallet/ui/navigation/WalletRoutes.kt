package org.aristonis.mywallet.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.icons.WalletIcons

/** The single argument any editor needs: which row is being edited. */
const val ARG_ID = "id"

/**
 * The four places the navigation bar goes. Each is a nested graph, not a single screen — that is
 * what gives a tab a history of its own, so leaving Home part-way through managing accounts and
 * coming back returns you to where you were rather than to the top.
 */
enum class WalletTab(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.tab_home, WalletIcons.Home),
    TRANSACTIONS("transactions", R.string.tab_transactions, WalletIcons.Transactions),
    TRACKING("tracking", R.string.tab_tracking, WalletIcons.Tracking),
    SETTINGS("settings", R.string.tab_settings, WalletIcons.Settings),
    ;

    /** The screen a tab opens on, and the destination its stack is rooted at. */
    val rootRoute: String get() = "$route/${Leaf.ROOT}"

    /** A supporting screen pushed above this tab. */
    fun child(leaf: String): String = "$route/$leaf"

    /** A supporting screen that edits one row. */
    fun child(leaf: String, id: Long): String = "$route/$leaf/$id"

    /** The pattern the graph registers for [child] with an id. */
    fun childPattern(leaf: String): String = "$route/$leaf/{$ARG_ID}"
}

/**
 * The screens a tab can push. Naming them once and namespacing them per tab is what keeps "Exchange
 * rates opened from Home" and "Exchange rates opened from Settings" separate: same screen, two
 * destinations, each returning to the tab the user actually came from.
 */
object Leaf {
    const val ROOT = "root"
    const val ADD_TRANSACTION = "addTransaction"
    const val EDIT_TRANSACTION = "editTransaction"
    const val ADD_ACCOUNT = "addAccount"
    const val MANAGE_ACCOUNTS = "manageAccounts"
    const val EDIT_ACCOUNT = "editAccount"
    const val RATES = "rates"
    const val CATEGORIES = "categories"
    const val BASE_CURRENCY = "baseCurrency"
    const val ABOUT = "about"

    /** Every supporting screen a tab can push that needs no argument. [ROOT] is the tab itself. */
    val all = listOf(ADD_TRANSACTION, ADD_ACCOUNT, MANAGE_ACCOUNTS, RATES, CATEGORIES, BASE_CURRENCY, ABOUT)

    /** Every leaf that edits one row. */
    val withId = listOf(EDIT_TRANSACTION, EDIT_ACCOUNT)

    /**
     * Leaves that end in one committing action. These give up the navigation bar so nothing sits
     * under their primary button.
     */
    val forms = setOf(ADD_TRANSACTION, EDIT_TRANSACTION, ADD_ACCOUNT, EDIT_ACCOUNT, BASE_CURRENCY)
}

/** Which tab a destination belongs to, or null if it is outside the tabbed shell. */
fun tabOf(route: String?): WalletTab? =
    route?.substringBefore('/')?.let { prefix -> WalletTab.entries.firstOrNull { it.route == prefix } }

/** Whether a destination is one of the four tab roots. */
fun isTopLevel(route: String?): Boolean =
    route != null && WalletTab.entries.any { it.rootRoute == route }

/**
 * Whether a destination wears the navigation bar.
 *
 * Everything inside a tab keeps it, not just the tab's own screen. That is what lets a user walk
 * away from a tab part-way through something and come back to where they were — without it, a tab's
 * saved back stack could never be left behind in the first place, and the four destinations stop
 * being reachable the moment you open anything.
 *
 * Forms are the exception. They end in one committing action of their own, and a second row of
 * destinations under a full-width Save button invites the wrong tap at the worst moment; their back
 * arrow is how you leave them.
 */
fun showsNavigationBar(route: String?): Boolean {
    val tab = tabOf(route) ?: return false
    val leaf = route?.removePrefix("${tab.route}/")?.substringBefore('/')
    return leaf != null && leaf !in Leaf.forms
}
