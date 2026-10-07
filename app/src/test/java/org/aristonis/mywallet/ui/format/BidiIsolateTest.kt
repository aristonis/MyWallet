package org.aristonis.mywallet.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A value substituted into a sentence is laid out by the paragraph around it. Under a right-to-left
 * layout, "Today · 28 أغسطس" came out as "أغسطس Today · 28" on a real device — every word present and
 * the sentence meaningless. Isolating the value is what stops the surrounding text reordering it.
 */
class BidiIsolateTest {

    private val firstStrongIsolate = '⁨'
    private val popDirectionalIsolate = '⁩'

    @Test
    fun `a value is wrapped in an isolate pair`() {
        val isolated = bidiIsolate("28 August")

        assertEquals(firstStrongIsolate, isolated.first())
        assertEquals(popDirectionalIsolate, isolated.last())
        assertTrue(isolated.contains("28 August"))
    }

    /** The isolate is invisible: what the user reads has to be unchanged. */
    @Test
    fun `nothing visible is added`() {
        val isolated = bidiIsolate("Cash")

        assertEquals("Cash", isolated.filterNot { it == firstStrongIsolate || it == popDirectionalIsolate })
    }

    /** A right-to-left value is the case this exists for, so it must be isolated too. */
    @Test
    fun `a right-to-left value is isolated the same way`() {
        val isolated = bidiIsolate("٢٨ أغسطس")

        assertEquals(firstStrongIsolate, isolated.first())
        assertEquals(popDirectionalIsolate, isolated.last())
    }

    @Test
    fun `an empty value still produces a well-formed pair`() {
        assertEquals("$firstStrongIsolate$popDirectionalIsolate", bidiIsolate(""))
    }

    /** Two isolated values in one sentence stay separately isolated rather than merging. */
    @Test
    fun `isolating twice keeps the pairs balanced`() {
        val sentence = "${bidiIsolate("Cash")} → ${bidiIsolate("Savings")}"

        assertEquals(2, sentence.count { it == firstStrongIsolate })
        assertEquals(2, sentence.count { it == popDirectionalIsolate })
    }
}
