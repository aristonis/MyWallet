package org.aristonis.mywallet.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = TealPrimaryLight,
    secondary = TealSecondaryLight,
    tertiary = TealTertiaryLight,
)

private val DarkColors = darkColorScheme(
    primary = TealPrimaryDark,
    secondary = TealSecondaryDark,
    tertiary = TealTertiaryDark,
)

/**
 * App theme. Dynamic color on Android 12+ (per design-constraints.md), otherwise the
 * teal-seeded light/dark scheme. Light + dark are both first-class (FR-24).
 */
@Composable
fun MyWalletTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
