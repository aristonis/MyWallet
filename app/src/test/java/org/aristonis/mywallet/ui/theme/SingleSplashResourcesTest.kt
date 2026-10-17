package org.aristonis.mywallet.ui.theme

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SingleSplashResourcesTest {
    @Test
    fun legacyStartingWindowIncludesTheCompatLogoAndBottomCopyright() {
        assertEquals("@drawable/splash_starting_window", themeItem("values", "android:windowBackground"))
        val layers = document("drawable/splash_starting_window.xml").getElementsByTagName("item")
        assertEquals(5, layers.length)
        val icon = layers.item(2) as Element
        val mask = layers.item(3) as Element
        val copyright = layers.item(4) as Element
        assertEquals("?windowSplashScreenAnimatedIcon", icon.getAttribute("android:drawable"))
        assertEquals("center", icon.getAttribute("android:gravity"))
        assertEquals("@dimen/splash_starting_icon_size", icon.getAttribute("android:width"))
        assertEquals("@dimen/splash_starting_icon_mask_size", mask.getAttribute("android:width"))
        val stroke = mask.getElementsByTagName("stroke").item(0) as Element
        assertEquals("@dimen/splash_starting_icon_mask_stroke", stroke.getAttribute("android:width"))
        val dimensions = document("values/dimens_splash.xml").getElementsByTagName("dimen")
        assertEquals(listOf("240dp", "342dp", "92dp"),
            (0 until dimensions.length).map { dimensions.item(it).textContent.trim() })
        assertEquals("@drawable/splash_copyright", copyright.getAttribute("android:drawable"))
        assertEquals("bottom|center_horizontal", copyright.getAttribute("android:gravity"))
    }

    @Test
    fun android12StartingWindowUsesTheSameCopyrightAsItsNativeBranding() {
        assertEquals("@drawable/splash_copyright", themeItem("values-v31", "android:windowSplashScreenBrandingImage"))
    }

    @Test
    fun platformAndCompatSplashKeepTheSameLogoAndTheme() {
        listOf("windowSplashScreenBackground", "windowSplashScreenIconBackgroundColor",
            "windowSplashScreenAnimatedIcon", "postSplashScreenTheme").forEach { name ->
            assertEquals(themeItem("values", name), themeItem("values-v31", name))
        }
    }

    @Test
    fun copyrightArtworkFitsTheNativeBrandingSlotAndHasVisibleGlyphs() {
        val artwork = document("drawable/splash_copyright.xml")
        assertEquals("200dp", artwork.documentElement.getAttribute("android:width"))
        assertEquals("80dp", artwork.documentElement.getAttribute("android:height"))
        val paths = artwork.getElementsByTagName("path")
        assertTrue("Copyright lettering must be drawn", paths.length > 0)
        assertTrue((paths.item(0) as Element).getAttribute("android:pathData").isNotBlank())
    }

    private fun themeItem(qualifier: String, name: String): String {
        val styles = document("$qualifier/themes.xml").getElementsByTagName("style")
        val starting = (0 until styles.length).map { styles.item(it) as Element }
            .single { it.getAttribute("name") == "Theme.MyWallet.Starting" }
        val items = starting.getElementsByTagName("item")
        val value = (0 until items.length).map { items.item(it) as Element }
            .singleOrNull { it.getAttribute("name") == name }?.textContent?.trim()
        if (value != null) return value
        val bases = document("values/themes.xml").getElementsByTagName("style")
        val base = (0 until bases.length).map { bases.item(it) as Element }
            .single { it.getAttribute("name") == starting.getAttribute("parent") }
        val inherited = base.getElementsByTagName("item")
        return (0 until inherited.length).map { inherited.item(it) as Element }
            .single { it.getAttribute("name") == name }.textContent.trim()
    }

    private fun document(path: String) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder().parse(File("src/main/res/$path"))
}
