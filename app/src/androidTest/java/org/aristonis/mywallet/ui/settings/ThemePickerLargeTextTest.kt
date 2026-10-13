package org.aristonis.mywallet.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemePickerLargeTextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun darkChoiceIsFullyVisibleAndSelectableAtTwoHundredPercentInRtl() {
        var selectedTheme: ThemePreference? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    ThemePickerSheet(
                        currentTheme = ThemePreference.SYSTEM,
                        onThemeSelected = { selectedTheme = it },
                        onDismiss = {},
                    )
                }
            }
        }

        compose.onNodeWithText("System")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
        val darkChoice = compose.onNodeWithText("Dark")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsNotSelected()
            .assertIsDisplayed()
            .assertHasClickAction()
        val visible = darkChoice.getBoundsInRoot()
        val full = darkChoice.getUnclippedBoundsInRoot()
        assertEquals("The Dark choice must clear the bottom safe area", full.bottom.value, visible.bottom.value, 0.5f)

        darkChoice.performClick()
        compose.runOnIdle { assertEquals(ThemePreference.DARK, selectedTheme) }
    }
}
