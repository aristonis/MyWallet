package org.aristonis.mywallet.ui.window

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Five segments share one row's width equally, so each label gets a fifth of the screen. At 200% text
 * "All time" has nowhere to go but truncation, and a period the user cannot read is a period they
 * cannot knowingly choose. Past that size the same choice becomes a menu.
 *
 * What these pin is that the choice survives the change of shape: every period still reachable, one
 * still selected, and picking one still reports that period and nothing else.
 */
@RunWith(AndroidJUnit4::class)
class PeriodSelectorTest {

    @get:Rule val compose = createComposeRule()

    private val allPeriods = listOf("Day", "Week", "Month", "Year", "All time")

    private fun show(
        fontScale: Float,
        selected: TrackingPeriod = TrackingPeriod.MONTH,
        onSelectPeriod: (TrackingPeriod) -> Unit = {},
    ) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = fontScale),
                ) {
                    DateWindowBar(
                        window = TrackingWindow.Period(selected, LocalDate.of(2026, 7, 15)),
                        actions = DateWindowActions.None.copy(onSelectPeriod = onSelectPeriod),
                    )
                }
            }
        }
    }

    // --- standard text: the segmented row ---------------------------------------------------------

    @Test
    fun atStandardTextEveryPeriodIsOnScreenAtOnce() {
        show(fontScale = 1f)

        allPeriods.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun atStandardTextTheCurrentPeriodReadsAsSelected() {
        show(fontScale = 1f, selected = TrackingPeriod.YEAR)

        compose.onNodeWithText("Year").assertIsSelected()
    }

    @Test
    fun atStandardTextPickingAPeriodReportsIt() {
        var picked: TrackingPeriod? = null
        show(fontScale = 1f, onSelectPeriod = { picked = it })

        compose.onNodeWithText("All time").performClick()

        assertEquals(TrackingPeriod.ALL_TIME, picked)
    }

    // --- large text: the menu ---------------------------------------------------------------------

    /** The row is gone, and what replaces it says which period is in effect without being opened. */
    @Test
    fun atLargeTextTheRowIsReplacedByANamedControl() {
        show(fontScale = 2f, selected = TrackingPeriod.MONTH)

        compose.onNodeWithContentDescription("Period, Month").assertIsDisplayed()
        compose.onNodeWithText("Day").assertDoesNotExist()
        compose.onNodeWithText("All time").assertDoesNotExist()
    }

    @Test
    fun atLargeTextEveryPeriodIsStillReachable() {
        show(fontScale = 2f)

        compose.onNodeWithContentDescription("Period, Month").performClick()

        allPeriods.forEach { menuEntry(it).assertIsDisplayed() }
    }

    @Test
    fun atLargeTextPickingAPeriodReportsIt() {
        var picked: TrackingPeriod? = null
        show(fontScale = 2f, onSelectPeriod = { picked = it })

        compose.onNodeWithContentDescription("Period, Month").performClick()
        menuEntry("All time").performClick()

        assertEquals(TrackingPeriod.ALL_TIME, picked)
    }

    /** Single selection, not a set of toggles: exactly one entry is marked as the current one. */
    @Test
    fun atLargeTextExactlyOnePeriodReadsAsSelected() {
        show(fontScale = 2f, selected = TrackingPeriod.WEEK)

        compose.onNodeWithContentDescription("Period, Week").performClick()

        menuEntry("Week").assertIsSelected()
        listOf("Day", "Month", "Year", "All time").forEach { menuEntry(it).assertIsNotSelected() }
    }

    /** Choosing closes the menu, so the screen returns to the report rather than staying covered. */
    @Test
    fun atLargeTextChoosingClosesTheMenu() {
        show(fontScale = 2f)

        compose.onNodeWithContentDescription("Period, Month").performClick()
        menuEntry("Day").performClick()

        compose.onNodeWithText("All time").assertDoesNotExist()
    }

    /**
     * The closed control stays composed behind the open menu, so its label and the matching menu entry
     * are both on screen. Only the entry carries a radio role, which is what tells the two apart.
     */
    private fun menuEntry(label: String) = compose.onNode(
        hasText(label).and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)),
    )
}
