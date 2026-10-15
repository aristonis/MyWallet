package org.aristonis.mywallet.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletThemePaletteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun lightFallbackKeepsFinancialMeaning() = checkPalette(isDark = false, isDynamic = false)

    @Test
    fun darkFallbackKeepsFinancialMeaning() = checkPalette(isDark = true, isDynamic = false)

    @Test
    fun lightDynamicPaletteKeepsFinancialMeaning() = checkPalette(isDark = false, isDynamic = true)

    @Test
    fun darkDynamicPaletteKeepsFinancialMeaning() = checkPalette(isDark = true, isDynamic = true)

    private fun checkPalette(isDark: Boolean, isDynamic: Boolean) {
        if (isDynamic) assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        var primary = Color.Unspecified
        var income = Color.Unspecified
        var expense = Color.Unspecified
        var surface = Color.Unspecified
        compose.setContent {
            MyWalletTheme(darkTheme = isDark, dynamicColor = isDynamic) {
                primary = MaterialTheme.colorScheme.primary
                income = WalletTheme.colors.income
                expense = WalletTheme.colors.expense
                surface = MaterialTheme.colorScheme.surface
            }
        }

        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val expectedPrimary = when {
                isDynamic && isDark -> dynamicDarkColorScheme(context).primary
                isDynamic -> dynamicLightColorScheme(context).primary
                isDark -> DarkColors.primary
                else -> LightColors.primary
            }
            assertEquals(expectedPrimary, primary)
            assertEquals(if (isDark) Color(0xFF34D399) else Color(0xFF065F46), income)
            assertEquals(if (isDark) Color(0xFFF87171) else Color(0xFFB91C1C), expense)
            assertReadableOnSurface(income, surface)
            assertReadableOnSurface(expense, surface)
        }
    }

    private fun assertReadableOnSurface(foreground: Color, background: Color) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        val ratio = (lighter + 0.05f) / (darker + 0.05f)
        org.junit.Assert.assertTrue("Text contrast must be at least 4.5:1; was $ratio", ratio >= 4.5f)
    }
}
