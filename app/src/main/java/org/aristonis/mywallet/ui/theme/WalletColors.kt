package org.aristonis.mywallet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.aristonis.mywallet.ui.format.AmountRole

/**
 * The ledger colours Material 3 does not name. Screens read these through [WalletTheme] so no screen
 * ever spells out a hex value, and so the meaning of "income" is decided once instead of per screen.
 */
@Immutable
data class WalletColors(
    val income: Color,
    val expense: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightWalletColors = WalletColors(
    income = IncomeLight,
    expense = ExpenseLight,
    warning = WarningLight,
    warningContainer = WarningContainerLight,
    onWarningContainer = OnWarningContainerLight,
)

private val DarkWalletColors = WalletColors(
    income = IncomeDark,
    expense = ExpenseDark,
    warning = WarningDark,
    warningContainer = WarningContainerDark,
    onWarningContainer = OnWarningContainerDark,
)

internal fun walletColors(darkTheme: Boolean): WalletColors =
    if (darkTheme) DarkWalletColors else LightWalletColors

/**
 * Defaults to the light set so a composable previewed outside [MyWalletTheme] still renders readable
 * amounts instead of a transparent one.
 */
internal val LocalWalletColors = staticCompositionLocalOf { LightWalletColors }

/** Companion to [MaterialTheme][androidx.compose.material3.MaterialTheme] for the ledger roles. */
object WalletTheme {
    val colors: WalletColors
        @Composable @ReadOnlyComposable get() = LocalWalletColors.current
}

/**
 * The colour an amount is drawn in. The sign and the leading icon carry the same meaning on screen,
 * so a user who cannot separate the two hues still reads the row correctly.
 */
@Composable
@ReadOnlyComposable
fun amountColor(role: AmountRole): Color = when (role) {
    AmountRole.INCOME -> WalletTheme.colors.income
    AmountRole.EXPENSE -> WalletTheme.colors.expense
    // A transfer moved money between the user's own accounts, so it is neither a gain nor a loss.
    // onSurfaceVariant states that: present, readable, and visibly not one of the two signed colours.
    AmountRole.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}
