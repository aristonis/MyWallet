package org.aristonis.mywallet.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.theme.WalletTheme

internal const val TAG_BALANCE_CARD = "home-balance-card"

/** A zero-width space: an allowed line break that draws nothing. */
private const val BREAK_OPPORTUNITY = "\u200B"

/** A grouping separator between digits, with three digits after it. */
private val GROUP_SEPARATOR = Regex("(?<=\\d)([,.\u00A0\u202F'])(?=\\d{3})")

/**
 * [display] with a break allowed after each grouping separator. At large text a long figure cannot
 * fit one line, and without this the line breaker splits it inside a digit group ("28,930,869,58" /
 * "9"); with it the figure wraps between groups ("28,930," / "869,589").
 */
internal fun breakableAtGroups(display: String): String = display.replace(GROUP_SEPARATOR, "$1$BREAK_OPPORTUNITY")

/** Same OpenType feature MoneyText draws with, so the balance digits line up like every other amount. */
private const val TABULAR_FIGURES = "tnum"

/** Strength of the blue wash at the top of the balance card. */
private const val WASH_ALPHA = 0.14f

/** Strength of the tinted disc behind each figure's icon. */
private const val BADGE_ALPHA = 0.16f

/**
 * The summary the screen exists to show: one card with the balance, then the pocket, owed-to-me and
 * I-owe figures as labelled rows inside it. The owed and owe rows carry the income and expense hues
 * on their icons and amounts, so the two debt directions are told apart by colour as well as by icon
 * and label. A missing
 * rate is recoverable and the card is the way to recover it: tapping opens the rates screen.
 */
@Composable
internal fun HomeSummaryHero(summary: HomeSummaryState, onManageRates: () -> Unit, onDebts: () -> Unit) {
    when (summary) {
        HomeSummaryState.Loading -> CircularProgressIndicator()
        is HomeSummaryState.MissingRate -> StatusCard(StatusTone.WARNING,
            stringResource(R.string.home_missing_rate, summary.currencyCode),
            iconDescription = stringResource(R.string.cd_warning), onClick = onManageRates)
        is HomeSummaryState.Amounts -> BalanceCard(summary, onDebts)
    }
}

@Composable
private fun BalanceCard(summary: HomeSummaryState.Amounts, onDebts: () -> Unit) {
    val dimensions = WalletTheme.dimensions
    val scheme = MaterialTheme.colorScheme
    // A faint wash of the primary blue at the top of the card, fading into it: the one place on the
    // screen that carries the brand colour as a surface rather than as a control.
    val wash = Brush.verticalGradient(listOf(scheme.primary.copy(alpha = WASH_ALPHA), Color.Transparent))
    Card(
        modifier = Modifier.fillMaxWidth().testTag(TAG_BALANCE_CARD),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer, contentColor = scheme.onSurface),
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(
            Modifier.background(wash).padding(dimensions.sectionGap),
            verticalArrangement = Arrangement.spacedBy(dimensions.regionGap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(dimensions.grid)) {
                Text(stringResource(R.string.home_balance), style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant)
                BalanceAmount(summary.balanceDisplay)
            }
            HorizontalDivider(color = scheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(dimensions.grid)) {
                FigureRow(WalletIcons.AccountDefault, stringResource(R.string.home_in_my_pocket),
                    summary.pocketDisplay, scheme.onSurface, scheme.primary, null)
                FigureRow(WalletIcons.Lend, stringResource(R.string.home_owed_to_me),
                    summary.owedToMeDisplay, WalletTheme.colors.income, WalletTheme.colors.income, onDebts)
                FigureRow(WalletIcons.Borrow, stringResource(R.string.home_i_owe),
                    summary.iOweDisplay, WalletTheme.colors.expense, WalletTheme.colors.expense, onDebts)
            }
        }
    }
}

/**
 * The balance figure large and bold, the currency code small after it. Both are one text, so at
 * large sizes the code wraps as a whole onto the next line and the figure wraps between digit
 * groups, instead of the code being squeezed into a column beside the figure. Drawn left to right
 * in every locale, as [MoneyText] draws money. A display without a trailing code is drawn whole.
 */
@Composable
private fun BalanceAmount(display: String) {
    val code = display.substringAfterLast(' ', "")
    val hasCode = code.isNotEmpty() && code.all { it.isLetter() }
    val figure = breakableAtGroups(if (hasCode) display.substringBeforeLast(' ') else display)
    val codeStyle = MaterialTheme.typography.titleMedium
    val text = buildAnnotatedString {
        append(figure)
        if (hasCode) {
            append(' ')
            withStyle(SpanStyle(fontSize = codeStyle.fontSize, fontWeight = FontWeight.Medium)) { append(code) }
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(text, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold,
            fontFeatureSettings = TABULAR_FIGURES))
    }
}

/**
 * One figure row: the icon on a disc of its own hue, then the label with the amount under it.
 * TalkBack still hears the label once, from the row's description; the visible copy is hidden from it
 * so it is not read twice.
 */
@Composable
private fun FigureRow(icon: ImageVector, label: String, amount: String, tint: Color, accent: Color, onClick: (() -> Unit)?) {
    val dimensions = WalletTheme.dimensions
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(dimensions.cornerMedium)
    val tap = if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick)
    Row(
        Modifier.fillMaxWidth().clip(shape).then(tap)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .heightIn(min = dimensions.minTouchTarget)
            .padding(vertical = dimensions.relatedGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensions.iconContentGap),
    ) {
        Box(
            Modifier.size(dimensions.iconSize + dimensions.relatedGap * 2).clip(CircleShape)
                .background(accent.copy(alpha = BADGE_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(dimensions.iconSize))
        }
        // Label over amount rather than beside it: a long balance then never squeezes the label into
        // a wrap, and both grow with the font scale on their own line.
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {})
            MoneyText(breakableAtGroups(amount), style = MaterialTheme.typography.titleMedium, color = tint)
        }
    }
}
