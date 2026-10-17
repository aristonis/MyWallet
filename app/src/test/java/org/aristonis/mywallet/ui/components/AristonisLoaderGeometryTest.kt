package org.aristonis.mywallet.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AristonisLoaderGeometryTest {
    @Test
    fun `native polygons retain all approved coordinates in both layout directions`() {
        assertEquals(listOf(1.75f, 7.75f, 194f, 194f), LoaderGeometry.viewBox)
        assertEquals(
            listOf(
                listOf(98.5f, 11f, 191.5f, 198.5f, 180f, 198.5f, 99f, 33.5f, 17.5f, 198.5f, 6f, 198.5f),
                listOf(98.5f, 66f, 164.5f, 198.5f, 154f, 198.5f, 99f, 89f, 44.5f, 198.5f, 33f, 198.5f),
                listOf(92.5f, 152f, 142f, 152f, 147f, 162.5f, 86f, 162.5f),
                listOf(134f, 188.5f, 187.5f, 188.5f, 190.5f, 198.5f, 131f, 198.5f),
            ),
            LoaderGeometry.polygons,
        )
        assertEquals(2.5f, LoaderGeometry.outlineWidth, 0f)
        assertEquals(listOf(0f, 0.55f, 1f), LoaderGeometry.goldStops)
    }

    @Test
    fun `palettes match the brand tones independently of system theme`() {
        assertEquals(listOf(0xFFE7CF92, 0xFFC49A3A, 0xFFA87C2A), loaderPalette(true).gold)
        assertEquals(0xFFF4F1E8, loaderPalette(true).text)
        assertEquals(0xFF0B1F3A, loaderPalette(true).backdrop)
        assertEquals(listOf(0xFFC9A24B, 0xFFA87C2A, 0xFF7E5C1C), loaderPalette(false).gold)
        assertEquals(0xFF0B1F3A, loaderPalette(false).text)
        assertEquals(0xFFF4F1E8, loaderPalette(false).backdrop)
        assertEquals(0.9f, LoaderGeometry.backdropAlpha, 0f)
    }
}
