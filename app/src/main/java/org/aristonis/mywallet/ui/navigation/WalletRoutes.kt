package org.aristonis.mywallet.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.ui.icons.WalletIcons

/** The single argument any editor needs: which row is being edited. */
const val ARG_ID = "id"

/**
 * Optional query argument of the repayment form: the repayment being edited. Left out, the form
 * records a new one.
 */
const val ARG_REPAYMENT_ID = "repaymentId"

/** Optional add-transaction origin, retained with its back-stack entry. */
const val ARG_DEBT_SIDE = "debtSide"

/** Optional add-transaction origin: the account the user came from. Left out, the form picks its own. */
const val ARG_ACCOUNT_ID = "accountId"

/** Optional add-transaction origin: opens a transfer into [ARG_ACCOUNT_ID], the way Pay card does. */
const val ARG_PAY_CARD = "payCard"

/** The value [ARG_ACCOUNT_ID] takes when no account was passed; ids start at 1. */
const val NO_ACCOUNT_ID = 0L

/** The value [ARG_REPAYMENT_ID] takes when no repayment is being edited; ids start at 1. */
const val NO_REPAYMENT_ID = 0L

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

    /** The debt-list choice is an optional argument of the existing add form. */
    fun addTransactionPattern(): String =
        "${child(Leaf.ADD_TRANSACTION)}?$ARG_DEBT_SIDE={$ARG_DEBT_SIDE}&$ARG_ACCOUNT_ID={$ARG_ACCOUNT_ID}" +
            "&$ARG_PAY_CARD={$ARG_PAY_CARD}"

    /** Add transaction with [accountId] already chosen, as from an account's detail page. */
    fun addForAccountRoute(accountId: Long): String = "${child(Leaf.ADD_TRANSACTION)}?$ARG_ACCOUNT_ID=$accountId"

    /** Add transaction as a transfer into the card [accountId]; the source is still the user's to pick. */
    fun payCardRoute(accountId: Long): String =
        "${child(Leaf.ADD_TRANSACTION)}?$ARG_ACCOUNT_ID=$accountId&$ARG_PAY_CARD=true"

    fun addDebtRoute(side: DebtSide): String = "${child(Leaf.ADD_TRANSACTION)}?$ARG_DEBT_SIDE=${side.name}"

    /** The repayment form's pattern: the debt id in the path, the repayment being edited as a query. */
    fun repaymentPattern(): String =
        "${childPattern(Leaf.RECORD_REPAYMENT)}?$ARG_REPAYMENT_ID={$ARG_REPAYMENT_ID}"

    /** Opens the repayment form for [debtId]; a non-null [repaymentId] edits that repayment. */
    fun repaymentRoute(debtId: Long, repaymentId: Long? = null): String =
        child(Leaf.RECORD_REPAYMENT, debtId) + (repaymentId?.let { "?$ARG_REPAYMENT_ID=$it" } ?: "")
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
    const val DEDUCTIONS = "deductions"
    const val ADD_DEDUCTION = "addDeduction"
    const val EDIT_DEDUCTION = "editDeduction"
    const val BASE_CURRENCY = "baseCurrency"
    const val ABOUT = "about"
    const val APP_LICENSE = "appLicense"
    const val PRIVACY = "privacy"
    const val SUPPORT = "support"
    const val DEBTS = "debts"
    const val DEBT_DETAIL = "debtDetail"
    const val RECORD_REPAYMENT = "recordRepayment"
    const val ACCOUNT_DETAIL = "accountDetail"
    const val TRANSACTION_DETAIL = "transactionDetail"

    /** Every supporting screen a tab can push that needs no argument. [ROOT] is the tab itself. */
    val all = listOf(ADD_TRANSACTION, ADD_ACCOUNT, MANAGE_ACCOUNTS, RATES, CATEGORIES, BASE_CURRENCY,
        ABOUT, APP_LICENSE, PRIVACY, SUPPORT, DEBTS, DEDUCTIONS, ADD_DEDUCTION)

    /** Every leaf that edits one row. */
    val withId = listOf(EDIT_TRANSACTION, EDIT_ACCOUNT, DEBT_DETAIL, RECORD_REPAYMENT, EDIT_DEDUCTION,
        ACCOUNT_DETAIL, TRANSACTION_DETAIL)

    /** Leaves that end in one committing action. */
    val forms = setOf(ADD_TRANSACTION, EDIT_TRANSACTION, ADD_ACCOUNT, EDIT_ACCOUNT, BASE_CURRENCY, RECORD_REPAYMENT,
        ADD_DEDUCTION, EDIT_DEDUCTION)
}

/** Which tab a destination belongs to, or null if it is outside the tabbed shell. */
fun tabOf(route: String?): WalletTab? =
    route?.substringBefore('/')?.let { prefix -> WalletTab.entries.firstOrNull { it.route == prefix } }

/** Whether a destination is one of the four tab roots. */
fun isTopLevel(route: String?): Boolean =
    route != null && WalletTab.entries.any { it.rootRoute == route }

/** Only tab roots wear the navigation bar; Back returns child screens to their owning tab. */
fun showsNavigationBar(route: String?): Boolean = isTopLevel(route)
