package org.aristonis.mywallet.ui.settings

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that offline information links remain precise without changing their displayed text. */
internal class InformationLinksTest {

    @Test
    fun `URL and email become links without changing the information text`() {
        val url = "https://github.com/aristonis/MyWallet/issues"
        val email = "aristonis@outlook.com"
        val body = "Report at $url or email $email."
        val urlStart = body.indexOf(url)
        val emailStart = body.indexOf(email)

        val linkedText = linkedInformationText(
            body = body,
            linkStyle = TextLinkStyles(style = SpanStyle()),
        )
        val links = linkedText.getLinkAnnotations(0, linkedText.length)

        assertEquals(body, linkedText.text)
        assertEquals(2, links.size)
        assertEquals(
            listOf(Triple(url, urlStart, urlStart + url.length)),
            links.mapNotNull { range ->
                (range.item as? LinkAnnotation.Url)?.takeIf { it.url == url }?.let {
                    Triple(it.url, range.start, range.end)
                }
            },
        )
        assertEquals(
            listOf(Triple("mailto:$email", emailStart, emailStart + email.length)),
            links.mapNotNull { range ->
                (range.item as? LinkAnnotation.Url)?.takeIf { it.url == "mailto:$email" }?.let {
                    Triple(it.url, range.start, range.end)
                }
            },
        )
        assertTrue(linkedText.getLinkAnnotations(0, urlStart).isEmpty())
        assertTrue(linkedText.getLinkAnnotations(urlStart + url.length, emailStart).isEmpty())
        assertTrue(linkedText.getLinkAnnotations(emailStart + email.length, linkedText.length).isEmpty())
    }
}
