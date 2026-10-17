package org.aristonis.mywallet.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AristonisLoaderStillTest {
    private val stillMotion = object : MotionDurationScale { override val scaleFactor = 0f }
    @get:Rule val compose = createComposeRule(effectContext = stillMotion)

    @Test
    fun zeroScaleRevealsAfterDelayAndNeverNeedsAnAnimationFrameToFinish() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MyWalletTheme(darkTheme = false) {
                AristonisLoader(Modifier.size(240.dp).testTag("loader"))
            }
        }
        val hidden = compose.onNodeWithTag("loader").captureToImage()
        compose.mainClock.advanceTimeBy(LOADER_DELAY_MILLIS + 32)
        val visible = compose.onNodeWithTag("loader").captureToImage()
        assertTrue("A still filled mark must appear after the delay", pixels(hidden) != pixels(visible))
        compose.mainClock.advanceTimeBy(6400)
        assertEquals(pixels(visible), pixels(compose.onNodeWithTag("loader").captureToImage()))
    }

    @Test
    fun markIsPixelIdenticalAcrossRtlAndBrandCopyWrapsInsideTwoHundredPercentBody() {
        compose.mainClock.autoAdvance = false
        val direction = mutableStateOf(LayoutDirection.Ltr)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides direction.value,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(darkTheme = true) {
                    Box(Modifier.size(180.dp, 320.dp).testTag("body")) { AristonisLoader() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(LOADER_DELAY_MILLIS + 32)
        val mark = compose.onNodeWithTag("aristonis_loader_mark", useUnmergedTree = true)
        val ltr = pixels(mark.captureToImage())
        compose.runOnIdle { direction.value = LayoutDirection.Rtl }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(ltr, pixels(mark.captureToImage()))
        val body = compose.onNodeWithTag("body").getUnclippedBoundsInRoot()
        val message = compose.onNodeWithTag("aristonis_loader_message", useUnmergedTree = true)
        message.assertExists()
        val bounds = message.getUnclippedBoundsInRoot()
        assertTrue("Copy must wrap over multiple lines at 200%", (bounds.bottom - bounds.top).value > 32f)
        assertTrue(bounds.left >= body.left && bounds.right <= body.right)
        assertTrue(bounds.top >= body.top && bounds.bottom <= body.bottom)
    }

    private fun pixels(image: ImageBitmap): List<Int> {
        val map = image.toPixelMap()
        return (0 until image.height).flatMap { y -> (0 until image.width).map { x -> map[x, y].hashCode() } }
    }
}
