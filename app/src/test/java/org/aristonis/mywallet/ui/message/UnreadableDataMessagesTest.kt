package org.aristonis.mywallet.ui.message

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the user is told when stored data cannot be read. The mapping names the fault and the
 * strings word it; the Home wording tells the user to update the app and to leave the app data
 * alone, because clearing it would destroy the only copy of the rows that cannot be read yet.
 */
class UnreadableDataMessagesTest {

    private fun string(name: String): String? {
        val xml = File("src/main/res/values/strings.xml").readText()
        return Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.replace("\\'", "'")
    }

    @Test
    fun `a stored row nothing can read maps to the data unreadable message through every entry point`() {
        val failure = WalletException.UnreadableRow(42, "FROM_THE_FUTURE")

        assertEquals(UiMessage.DataUnreadable, failure.toUiMessage())
        assertEquals(UiMessage.DataUnreadable, (failure as Throwable).toUiMessage())
    }

    @Test
    fun `a category of the wrong kind for the entry maps to its own message through every entry point`() {
        val failure = WalletException.CategoryWrongKind(6)

        assertEquals(UiMessage.CategoryWrongKind, failure.toUiMessage())
        assertEquals(UiMessage.CategoryWrongKind, (failure as Throwable).toUiMessage())
    }

    @Test
    fun `both messages have wording of their own`() {
        assertTrue(string("msg_data_unreadable").orEmpty().isNotBlank())
        assertTrue(string("msg_category_wrong_kind").orEmpty().isNotBlank())
        assertFalse(string("msg_data_unreadable") == string("msg_category_wrong_kind"))
    }

    @Test
    fun `Home tells the user to update MyWallet and not to clear the app data`() {
        val home = string("home_data_unreadable")

        assertNotNull("home_data_unreadable is missing from strings.xml", home)
        assertTrue(home!!, home.contains("Update MyWallet. Don't clear the app data."))
    }

    @Test
    fun `Home no longer advises a backup export in the unreadable message`() {
        val home = string("home_data_unreadable").orEmpty()

        assertFalse(home, home.contains("export a backup", ignoreCase = true))
        assertTrue("it still says the data could not be read", home.contains("could not be read"))
    }
}
