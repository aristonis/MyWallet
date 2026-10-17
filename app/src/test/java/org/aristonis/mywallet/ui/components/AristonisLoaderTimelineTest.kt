package org.aristonis.mywallet.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AristonisLoaderTimelineTest {
    @Test
    fun `brand remains hidden for fast loads then fades over two hundred milliseconds`() {
        assertEquals(0f, loaderFrame(0).revealAlpha, 0f)
        assertEquals(0f, loaderFrame(299).revealAlpha, 0f)
        assertEquals(0f, loaderFrame(300).revealAlpha, 0f)
        assertTrue(loaderFrame(400).revealAlpha in 0.1f..0.99f)
        assertEquals(1f, loaderFrame(500).revealAlpha, 0f)
    }

    @Test
    fun `four traces start and finish at approved overlapping phase boundaries`() {
        val starts = listOf(0L, 448L, 1024L, 1280L)
        val ends = listOf(896L, 1216L, 1472L, 1728L)
        starts.indices.forEach { part ->
            assertEquals(0f, loaderFrame(starts[part]).traceFractions[part], 0f)
            assertTrue(loaderFrame((starts[part] + ends[part]) / 2).traceFractions[part] in 0.1f..0.99f)
            assertEquals(1f, loaderFrame(ends[part]).traceFractions[part], 0.0001f)
        }
    }

    @Test
    fun `fill shine hold and clear follow the approved cycle`() {
        assertEquals(0f, loaderFrame(1728).fillAlpha, 0f)
        assertTrue(loaderFrame(1888).fillAlpha in 0.1f..0.99f)
        assertEquals(1f, loaderFrame(2048).fillAlpha, 0f)
        assertNull(loaderFrame(2111).shineProgress)
        assertEquals(0f, loaderFrame(2112).shineProgress!!, 0f)
        assertTrue(loaderFrame(2400).shineProgress!! in 0.1f..0.99f)
        assertEquals(1f, loaderFrame(2688).shineProgress!!, 0f)
        assertEquals(1f, loaderFrame(2816).fillAlpha, 0f)
        assertTrue(loaderFrame(2944).fillAlpha in 0.01f..0.99f)
        assertTrue(loaderFrame(2944).outlineAlpha in 0.01f..0.99f)
        assertEquals(0f, loaderFrame(3072).fillAlpha, 0f)
        assertEquals(0f, loaderFrame(3199).outlineAlpha, 0f)
    }

    @Test
    fun `cycle and eight brand messages wrap without retaining previous traces`() {
        assertEquals(0, loaderFrame(3199).messageIndex)
        assertEquals(1, loaderFrame(3200).messageIndex)
        assertEquals(listOf(0f, 0f, 0f, 0f), loaderFrame(3200).traceFractions)
        assertEquals(7, loaderFrame(25599).messageIndex)
        assertEquals(0, loaderFrame(25600).messageIndex)
        assertEquals(1f, loaderFrame(25600).revealAlpha, 0f)
    }

    @Test
    fun `reduced motion has a filled mark without tracing or shine`() {
        listOf(0L, 500L, 1600L, 3000L, 3200L).forEach { elapsed ->
            val frame = loaderFrame(elapsed, reducedMotion = true)
            assertEquals(1f, frame.fillAlpha, 0f)
            assertEquals(0f, frame.outlineAlpha, 0f)
            assertEquals(listOf(0f, 0f, 0f, 0f), frame.traceFractions)
            assertNull(frame.shineProgress)
        }
    }

    @Test
    fun `zero animation scale remains still and reveal needs no animation clock`() {
        val before = loaderFrame(299, durationScale = 0f)
        val after = loaderFrame(300, durationScale = 0f)
        assertEquals(0f, before.revealAlpha, 0f)
        assertEquals(1f, after.revealAlpha, 0f)
        assertEquals(1f, after.fillAlpha, 0f)
        assertEquals(0f, after.outlineAlpha, 0f)
        assertNull(after.shineProgress)
        assertEquals(after, loaderFrame(100000, durationScale = 0f))
    }

    @Test
    fun `positive system scale stretches motion but not fast load delay`() {
        assertEquals(loaderFrame(800).traceFractions, loaderFrame(1600, durationScale = 2f).traceFractions)
        assertEquals(1, loaderFrame(6400, durationScale = 2f).messageIndex)
        assertEquals(0f, loaderFrame(299, durationScale = 2f).revealAlpha, 0f)
        assertEquals(1f, loaderFrame(700, durationScale = 2f).revealAlpha, 0f)
    }

    @Test
    fun `very long uptime stays bounded and reduced motion with zero scale is still`() {
        val frame = loaderFrame(Long.MAX_VALUE)
        assertTrue(frame.messageIndex in 0..7)
        assertTrue(frame.traceFractions.all { it in 0f..1f })
        assertEquals(
            loaderFrame(500, durationScale = 0f),
            loaderFrame(Long.MAX_VALUE, durationScale = 0f, reducedMotion = true),
        )
    }

    @Test
    fun `invalid duration scales use safe still motion and negative elapsed starts hidden`() {
        listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { scale ->
            assertEquals(loaderFrame(500, durationScale = 0f), loaderFrame(500, durationScale = scale))
        }
        assertEquals(loaderFrame(0), loaderFrame(-1))
    }
}
