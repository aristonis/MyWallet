package org.aristonis.mywallet.ui.components

/** Brand coordinates stay in their original orientation, including inside RTL layouts. */
internal object LoaderGeometry {
    val viewBox = listOf(1.75f, 7.75f, 194f, 194f)
    val polygons = listOf(
        listOf(98.5f, 11f, 191.5f, 198.5f, 180f, 198.5f, 99f, 33.5f, 17.5f, 198.5f, 6f, 198.5f),
        listOf(98.5f, 66f, 164.5f, 198.5f, 154f, 198.5f, 99f, 89f, 44.5f, 198.5f, 33f, 198.5f),
        listOf(92.5f, 152f, 142f, 152f, 147f, 162.5f, 86f, 162.5f),
        listOf(134f, 188.5f, 187.5f, 188.5f, 190.5f, 198.5f, 131f, 198.5f),
    )
    val goldStops = listOf(0f, 0.55f, 1f)
    const val outlineWidth = 2.5f
    const val backdropAlpha = 0.9f
}

internal data class LoaderPalette(val gold: List<Long>, val text: Long, val backdrop: Long)

internal fun loaderPalette(isDark: Boolean): LoaderPalette = if (isDark) {
    LoaderPalette(listOf(0xFFE7CF92, 0xFFC49A3A, 0xFFA87C2A), 0xFFF4F1E8, 0xFF0B1F3A)
} else {
    LoaderPalette(listOf(0xFFC9A24B, 0xFFA87C2A, 0xFF7E5C1C), 0xFF0B1F3A, 0xFFF4F1E8)
}
