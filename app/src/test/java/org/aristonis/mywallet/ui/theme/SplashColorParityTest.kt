package org.aristonis.mywallet.ui.theme

import androidx.compose.ui.graphics.toArgb
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

class SplashColorParityTest {
    @Test
    fun lightSplashBackground_matchesComposeBackground() {
        assertEquals(BackgroundLight.toArgb(), splashBackground("values"))
    }

    @Test
    fun nightSplashBackground_matchesComposeBackground() {
        assertEquals(BackgroundDark.toArgb(), splashBackground("values-night"))
    }

    @Test
    fun splashIcon_usesSupportedBackgroundThemeAndLauncherTeal() {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val themes = builder.parse(File("src/main/res/values/themes.xml"))
        val styles = themes.getElementsByTagName("style")
        val starting = (0 until styles.length).map { styles.item(it) as Element }
            .single { it.getAttribute("name") == "Base.Theme.MyWallet.Starting" }
        assertEquals("Theme.SplashScreen.IconBackground", starting.getAttribute("parent"))
        val items = starting.getElementsByTagName("item")
        val iconBackground = (0 until items.length).map { items.item(it) as Element }
            .single { it.getAttribute("name") == "windowSplashScreenIconBackgroundColor" }
        assertEquals("@color/splash_icon_background", iconBackground.textContent.trim())
        val launcher = builder.parse(File("src/main/res/drawable/ic_launcher_background.xml"))
        val path = launcher.getElementsByTagName("path").item(0) as Element
        val launcherColor = path.getAttribute("android:fillColor").removePrefix("#")
        val launcherArgb = ("FF" + launcherColor).toLong(16).toInt()

        assertEquals(launcherArgb, resourceColor("values", "splash_icon_background"))
    }

    private fun splashBackground(qualifier: String): Int =
        resourceColor(qualifier, "splash_background")

    private fun resourceColor(qualifier: String, name: String): Int {
        val colors = File("src/main/res/$qualifier/colors.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(colors)
        val entries = document.getElementsByTagName("color")
        val background = (0 until entries.length)
            .map { entries.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == name }
        assertNotNull("$qualifier must define $name", background)
        val hex = requireNotNull(background).textContent.trim().removePrefix("#")
        val argb = if (hex.length == 6) "FF$hex" else hex
        return argb.toLong(16).toInt()
    }
}
