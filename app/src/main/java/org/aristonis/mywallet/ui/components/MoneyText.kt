package org.aristonis.mywallet.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection

/**
 * OpenType tabular figures: every digit takes the same width, so a column of amounts lines up on the
 * decimal point and a ticking balance does not jitter as its digits change.
 */
private const val TABULAR_FIGURES = "tnum"

/**
 * Draws an already-formatted amount.
 *
 * Money is always laid out left-to-right, including under an Arabic or Hebrew locale: a figure such
 * as "−1,234.50 USD" reverses into nonsense if the surrounding RTL direction is allowed to reach it.
 * The rest of the row still mirrors — only the number holds its ground.
 *
 * Formatting itself belongs to the view-model, which knows the currency and the locale; this only
 * decides how the finished text is drawn.
 */
@Composable
fun MoneyText(
    amount: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            text = amount,
            modifier = modifier,
            style = style.copy(fontFeatureSettings = TABULAR_FIGURES),
            color = color,
        )
    }
}
