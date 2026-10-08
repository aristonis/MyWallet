package org.aristonis.mywallet.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector
import org.aristonis.mywallet.domain.model.AccountTypeRegistry

/**
 * The app's icon vocabulary, named by what it means rather than by which glyph it happens to be.
 * Screens go through these names so swapping a glyph is one edit here, and so two screens can never
 * end up illustrating the same idea with two different icons. Material Symbols Rounded throughout,
 * so every glyph shares the same soft corners and stroke weight.
 */
object WalletIcons {
    // Bottom navigation
    val Home: ImageVector = Icons.Rounded.Home
    val Transactions: ImageVector = Icons.AutoMirrored.Rounded.ReceiptLong
    val Tracking: ImageVector = Icons.Rounded.Insights
    val Settings: ImageVector = Icons.Rounded.Settings

    // Main action
    val Add: ImageVector = Icons.Rounded.Add

    // Transaction types
    val Income: ImageVector = Icons.Rounded.SouthWest
    val Expense: ImageVector = Icons.Rounded.NorthEast
    val Transfer: ImageVector = Icons.Rounded.SwapHoriz

    // Accounts
    val AccountDefault: ImageVector = Icons.Rounded.AccountBalanceWallet
    val AccountBank: ImageVector = Icons.Rounded.AccountBalance
    val AccountCard: ImageVector = Icons.Rounded.CreditCard
    val AccountCash: ImageVector = Icons.Rounded.Payments

    // Management
    val Edit: ImageVector = Icons.Rounded.Edit
    val Delete: ImageVector = Icons.Rounded.Delete
    val Archive: ImageVector = Icons.Rounded.Archive
    val Unarchive: ImageVector = Icons.Rounded.Unarchive
    val More: ImageVector = Icons.Rounded.MoreVert

    // Browse and filter
    val Search: ImageVector = Icons.Rounded.Search
    val Filter: ImageVector = Icons.Rounded.FilterList
    val Date: ImageVector = Icons.Rounded.CalendarToday
    val Forward: ImageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight
    val DateRange: ImageVector = Icons.Rounded.DateRange

    // Stepping through time. Auto-mirrored so "earlier" points the way a right-to-left reader reads.
    val Previous: ImageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft
    val Next: ImageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight

    // Settings
    val Rates: ImageVector = Icons.Rounded.CurrencyExchange
    val Categories: ImageVector = Icons.Rounded.Category
    val Backup: ImageVector = Icons.Rounded.Backup
    val Restore: ImageVector = Icons.Rounded.Restore
    val Appearance: ImageVector = Icons.Rounded.Palette
    val About: ImageVector = Icons.Rounded.Info

    // Status
    val Warning: ImageVector = Icons.Rounded.WarningAmber
    val Error: ImageVector = Icons.Rounded.ErrorOutline
    val Success: ImageVector = Icons.Rounded.CheckCircle

    // Marks which entry of a list is the current one. A bare tick, not the status glyph:
    // nothing has succeeded, this is simply the one in effect.
    val Selected: ImageVector = Icons.Rounded.Check
    val Offline: ImageVector = Icons.Rounded.CloudOff

    // Navigation and form affordances
    val Back: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack
    val Close: ImageVector = Icons.Rounded.Close
    val Expand: ImageVector = Icons.Rounded.ExpandMore
    val Collapse: ImageVector = Icons.Rounded.ExpandLess
}

/**
 * Account types are an open registry, so their icons are a lookup rather than a `when`: giving a
 * newly registered type its own glyph is an insert here, and a type nobody mapped still renders.
 */
private val accountTypeIcons: Map<String, ImageVector> = mapOf(
    AccountTypeRegistry.BuiltIns.CASH.key to WalletIcons.AccountCash,
    AccountTypeRegistry.BuiltIns.CARD.key to WalletIcons.AccountCard,
    AccountTypeRegistry.BuiltIns.BANK.key to WalletIcons.AccountBank,
)

fun accountTypeIcon(typeKey: String): ImageVector =
    accountTypeIcons[typeKey] ?: WalletIcons.AccountDefault
