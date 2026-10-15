package org.aristonis.mywallet.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseFormatterTest {

    @Test
    fun `the bundled license preserves every word while removing hard wraps`() {
        val raw = File("src/main/assets/LICENSE").readText()

        val blocks = formatLicenseText(raw)

        assertEquals(collapseWhitespace(raw), collapseWhitespace(blocks.asText()))
    }

    @Test
    fun `the bundled notice preserves every word while removing hard wraps`() {
        val raw = File("src/main/assets/NOTICE").readText()

        val blocks = formatLicenseText(raw)

        assertEquals(collapseWhitespace(raw), collapseWhitespace(blocks.asText()))
    }

    @Test
    fun `the Apache heading is a single title block`() {
        val blocks = formatLicenseText(File("src/main/assets/LICENSE").readText())

        val title = blocks.first() as LicenseBlock.Title

        assertEquals(
            listOf(
                "Apache License",
                "Version 2.0, January 2004",
                "http://www.apache.org/licenses/",
            ),
            title.lines,
        )
        assertEquals(1, blocks.count { it is LicenseBlock.Title })
    }

    @Test
    fun `numbered sections become headings`() {
        val blocks = formatLicenseText(File("src/main/assets/LICENSE").readText())

        assertTrue(blocks.any { it == LicenseBlock.Heading("1. Definitions.") })
    }

    @Test
    fun `the bundled license contains list items`() {
        val blocks = formatLicenseText(File("src/main/assets/LICENSE").readText())

        assertTrue(blocks.any { it is LicenseBlock.ListItem })
    }

    @Test
    fun `the bundled Apache URL is found exactly`() {
        val text = File("src/main/assets/LICENSE").readText()

        val urls = findUrls(text)

        val expected = "http://www.apache.org/licenses/"
        assertTrue(urls.any { text.substring(it) == expected })
    }

    @Test
    fun `URLs exclude surrounding sentence punctuation`() {
        val text = "Read (https://example.com/license)."
        val expected = "https://example.com/license"
        val start = text.indexOf(expected)

        val urls = findUrls(text)

        assertEquals(listOf(start until (start + expected.length)), urls)
    }

    @Test
    fun `empty input creates no blocks`() {
        assertEquals(emptyList<LicenseBlock>(), formatLicenseText(""))
    }

    @Test
    fun `hard wrapped lines become one paragraph`() {
        val blocks = formatLicenseText("A readable document\nshould not show hard wraps.")

        assertEquals(listOf(LicenseBlock.Paragraph("A readable document should not show hard wraps.")), blocks)
    }

    private fun List<LicenseBlock>.asText(): String = joinToString(" ") { block ->
        when (block) {
            is LicenseBlock.Title -> block.lines.joinToString(" ")
            is LicenseBlock.Heading -> block.text
            is LicenseBlock.Paragraph -> block.text
            is LicenseBlock.ListItem -> "${block.marker} ${block.text}"
        }
    }

    private fun collapseWhitespace(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}
