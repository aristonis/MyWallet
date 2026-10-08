package org.aristonis.mywallet.ui.window

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The bar is how both Tracking and Transactions say which dates they cover, so what it shows and
 * which steps it offers have to match the window exactly: arrows only where a neighbour exists, a
 * way back out of a custom range, and a label that stays readable at large text and in RTL.
 */
@RunWith(AndroidJUnit4::class)
class DateWindowBarTest {

    @get:Rule val compose = createComposeRule()

    private val august = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 10))

    private fun show(
        window: TrackingWindow,
        actions: DateWindowActions = DateWindowActions(),
        direction: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalLayoutDirection provides direction,
                    LocalDensity provides Density(density = base.density, fontScale = fontScale),
                ) {
                    DateWindowBar(window = window, actions = actions)
                }
            }
        }
    }

    @Test
    fun aMonthShowsItsLabelAndBothArrows() {
        show(august)

        compose.onNodeWithText("August 2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous period").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next period").assertIsDisplayed()
    }

    @Test
    fun arrowsStepTheWindow() {
        val steps = mutableListOf<Long>()
        show(august, DateWindowActions(onStep = { steps += it }))

        compose.onNodeWithContentDescription("Previous period").performClick()
        compose.onNodeWithContentDescription("Next period").performClick()

        assertEquals(listOf(-1L, 1L), steps)
    }

    @Test
    fun allTimeHidesTheArrows() {
        show(TrackingWindow.Period(TrackingPeriod.ALL_TIME, LocalDate.of(2026, 8, 10)))

        compose.onNodeWithContentDescription("Previous period").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next period").assertDoesNotExist()
    }

    @Test
    fun aCustomRangeHasNoArrowsAndCanBeCleared() {
        var cleared = false
        val custom = TrackingWindow.Custom(DateRange(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 17)))
        show(custom, DateWindowActions(onClearRange = { cleared = true }))

        compose.onNodeWithContentDescription("Previous period").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear date range").performClick()

        assertTrue(cleared)
    }

    @Test
    fun arrowsMirrorInRtl() {
        show(august, direction = LayoutDirection.Rtl)

        val previous = compose.onNodeWithContentDescription("Previous period").getBoundsInRoot()
        val next = compose.onNodeWithContentDescription("Next period").getBoundsInRoot()

        // Right-to-left reads from the right, so "earlier" sits on the right-hand side.
        assertTrue(previous.left > next.left)
    }

    @Test
    fun largeFontKeepsTheLabelReadable() {
        show(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 9, 10)), fontScale = 2f)

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("September 2026").assertIsDisplayed()
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)

        assertFalse(layouts.single().hasVisualOverflow)
    }
}
