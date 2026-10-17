package org.aristonis.mywallet.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Checks shipped attribution copy on the host; browser interaction is covered on Android. */
class AboutResourcesTest {
    @Test
    fun `about provides separate attribution and support copy in resources`() {
        val resourceFiles = File("src/main/res/values").listFiles().orEmpty()
            .filter { it.extension == "xml" }
        val factory = DocumentBuilderFactory.newInstance()
        val texts = resourceFiles.flatMap { file ->
            val strings = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until strings.length).map { strings.item(it).textContent }
        }

        assertTrue("About must provide a separate copyright line", "© 2026 Aristonis" in texts)
        assertTrue("About must offer the GitHub star action", "Star MyWallet on GitHub" in texts)
    }
}
