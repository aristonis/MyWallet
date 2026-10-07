package org.aristonis.mywallet.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the fallback scheme used on Android 11 and lower, where there is no dynamic colour to hide
 * a gap. A role nobody sets is not neutral — Material fills it with its baseline purple, and that
 * only becomes visible when some component finally reaches for it.
 *
 * The roles are read reflectively rather than listed by hand: if a future Material version adds one,
 * this test starts failing instead of quietly letting the new role default.
 */
class FallbackColorSchemeTest {

    /**
     * Roles where matching Material's baseline is the correct answer, not an oversight: pure white,
     * pure black, and the scrim. Every other role must come from the Calm Ledger palette.
     */
    private val allowedToMatchBaseline = setOf(
        "onPrimary", "onSecondary", "onTertiary", "onError",
        "scrim", "surfaceContainerLowest",
    )

    @Test
    fun `the light fallback sets every role Material knows about`() {
        assertNoRoleFallsBackToMaterial(walletScheme = LightColors, materialBaseline = lightColorScheme())
    }

    @Test
    fun `the dark fallback sets every role Material knows about`() {
        assertNoRoleFallsBackToMaterial(walletScheme = DarkColors, materialBaseline = darkColorScheme())
    }

    @Test
    fun `the light fallback keeps the approved Calm Ledger anchors`() {
        val scheme = LightColors

        assertEquals(Color(0xFF00696D), scheme.primary)
        assertEquals(Color(0xFF82F5F7), scheme.primaryContainer)
        assertEquals(Color(0xFFF8FAF9), scheme.surface)
        assertEquals(Color(0xFF191C1C), scheme.onSurface)
        assertEquals(Color(0xFF3F4948), scheme.onSurfaceVariant)
        assertEquals(Color(0xFF6F7978), scheme.outline)
    }

    @Test
    fun `the dark fallback keeps the approved Calm Ledger anchors`() {
        val scheme = DarkColors

        assertEquals(Color(0xFF4FD8DE), scheme.primary)
        assertEquals(Color(0xFF004F53), scheme.primaryContainer)
        assertEquals(Color(0xFF101414), scheme.surface)
        assertEquals(Color(0xFFE0E3E2), scheme.onSurface)
        assertEquals(Color(0xFFBFC9C7), scheme.onSurfaceVariant)
        assertEquals(Color(0xFF899392), scheme.outline)
    }

    /** Background and surface are the same tone here, so neither can be the odd one out. */
    @Test
    fun `background matches surface in both schemes`() {
        assertEquals(LightColors.surface, LightColors.background)
        assertEquals(DarkColors.surface, DarkColors.background)
    }

    private fun assertNoRoleFallsBackToMaterial(walletScheme: ColorScheme, materialBaseline: ColorScheme) {
        val ours = walletScheme.rolesByName()
        val baseline = materialBaseline.rolesByName()

        assertTrue("no colour roles were found by reflection", ours.size >= EXPECTED_ROLE_COUNT)

        val leaked = ours.filter { (name, color) ->
            name !in allowedToMatchBaseline && color == baseline[name]
        }
        assertEquals("roles still using Material's baseline value: ${leaked.keys.sorted()}", emptyMap<String, Color>(), leaked)
    }

    /** Every `Color` role on the scheme, keyed by role name. */
    private fun ColorScheme.rolesByName(): Map<String, Color> = javaClass.methods
        .filter { it.name.startsWith("get") && it.name.endsWith(COLOR_GETTER_SUFFIX) && it.parameterCount == 0 }
        .associate { method ->
            val name = method.name.removePrefix("get").removeSuffix(COLOR_GETTER_SUFFIX)
                .replaceFirstChar { it.lowercase() }
            // Color is a value class over a ULong, so the getter hands back the raw packed bits.
            name to Color((method.invoke(this) as Long).toULong())
        }

    private companion object {
        /** Kotlin's mangled suffix for a getter returning the `Color` value class. */
        private const val COLOR_GETTER_SUFFIX = "-0d7_KjU"

        /** Material 3 1.4 exposes 48 colour roles; fewer means reflection silently found nothing. */
        private const val EXPECTED_ROLE_COUNT = 48
    }
}
