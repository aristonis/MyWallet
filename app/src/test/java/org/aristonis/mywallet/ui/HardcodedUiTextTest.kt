package org.aristonis.mywallet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every word the user reads has to come from `strings.xml`, or an Arabic build quietly falls back to
 * English on whichever screen someone forgot.
 *
 * This reads the source rather than the running app because that is where the mistake is made, and
 * because it then catches a new screen on the day it is written instead of at translation time. The
 * scan runs over whole files, not line by line: a literal that has been wrapped onto its own line is
 * the easiest one to miss by eye and so the most important one to catch here.
 */
class HardcodedUiTextTest {

    @Test
    fun `no screen puts a literal in front of the user`() {
        val offenders = uiSources().flatMap { file -> file.offendingLiterals() }

        assertEquals(
            "hardcoded UI text found:\n${offenders.joinToString("\n")}",
            emptyList<String>(),
            offenders,
        )
    }

    /** A guard that scans no files would pass forever without proving anything. */
    @Test
    fun `the scan actually reaches the ui sources`() {
        val sources = uiSources()

        assertTrue("no UI sources found — the scan is looking in the wrong place", sources.size > 20)
        assertTrue(sources.any { it.name == "HomeScreen.kt" })
        assertTrue(sources.any { it.name == "SettingsScreen.kt" })
    }

    /** The scan has to survive the formatter moving an argument onto its own line. */
    @Test
    fun `a literal is caught whichever way the call is written`() {
        val written = listOf(
            """Text("Save")""",
            """Text(text = "Save")""",
            "Text(\n    \"Save\",\n)",
            "Text(\n    text = \"Save\",\n)",
            """Text(modifier = Modifier, text = "Save")""",
            """label = "Amount"""",
            """contentDescription = "Back"""",
        )

        written.forEach { source ->
            assertTrue("missed a literal in: $source", literalTexts(source).isNotEmpty())
        }
    }

    /**
     * The realistic shape. A modifier almost always comes first and almost always ends in a call, so
     * a scan that gives up at the first parenthesis is a scan that passes on most of the codebase.
     */
    @Test
    fun `a literal is caught behind a modifier argument`() {
        val written = listOf(
            """Text(modifier = Modifier.fillMaxWidth(), text = "Save")""",
            """Text(modifier = Modifier.padding(16.dp), text = "Save")""",
            """Text(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), text = "Save")""",
            """Text(modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, text = "Save")""",
            "Text(\n    modifier = Modifier.fillMaxWidth(),\n    text = \"Save\",\n)",
            "Text(\n    modifier = Modifier\n        .fillMaxWidth()\n        .padding(16.dp),\n    text = \"Save\",\n)",
            """Text(modifier = Modifier.fillMaxWidth(), "Save")""",
        )

        written.forEach { source ->
            assertTrue("missed a literal in: $source", literalTexts(source).isNotEmpty())
        }
    }

    /** Resolved text, formats and non-copy values are not findings; flagging them would train people to ignore this. */
    @Test
    fun `resolved text and non-copy values are left alone`() {
        val written = listOf(
            """Text(stringResource(R.string.action_save))""",
            """Text(text = stringResource(R.string.action_save))""",
            "Text(\n    text = stringResource(R.string.action_save),\n)",
            """Text(row.account.name)""",
            """Text(formatDate(date))""",
            """contentDescription = null""",
            """private const val DAY_SKELETON = "dMMM"""",
            """Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.action_save))""",
            "Text(\n    modifier = Modifier.fillMaxWidth(),\n    text = stringResource(R.string.action_save),\n)",
            """Text(modifier = Modifier.weight(1f), text = row.account.name)""",
            """Text(pluralStringResource(R.plurals.category_transaction_count, count, count))""",
        )

        written.forEach { source ->
            assertEquals("false positive in: $source", emptyList<String>(), literalTexts(source))
        }
    }

    private fun uiSources(): List<File> =
        File("src/main/java/org/aristonis/mywallet/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun File.offendingLiterals(): List<String> {
        val source = readText()
        return literalsIn(source)
            .filterNot { source.statementAround(it.offset).isPreviewData() }
            .map { "$name:${source.lineOf(it.offset)}  ${it.text}" }
    }

    /** A user-facing literal, and where in the file it sits. */
    private data class Finding(val offset: Int, val text: String)

    private fun literalsIn(source: String): List<Finding> =
        USER_FACING_SLOTS.flatMap { slot ->
            slot.findAll(source).map { Finding(it.range.first, it.groupValues.last()) }
        }

    private fun literalTexts(source: String): List<String> = literalsIn(source).map { it.text }

    private fun String.lineOf(offset: Int): Int = substring(0, offset).count { it == '\n' } + 1

    /**
     * The few lines around a finding. Sample data is often spread over several of them, so looking at
     * the offending line alone would miss the marker that identifies it.
     */
    private fun String.statementAround(offset: Int): String {
        val lines = lineSequence().toList()
        val line = lineOf(offset) - 1
        return lines.subList(maxOf(0, line - 3), minOf(lines.size, line + 2)).joinToString("\n")
    }

    /**
     * `@Preview` sample data is written for whoever is looking at the preview, never shipped, and
     * translating it would be pointless.
     */
    private fun String.isPreviewData(): Boolean = PREVIEW_MARKERS.any { it in this }

    private companion object {
        /**
         * One argument's value, up to the comma that ends it: identifier and dot runs, plus whole
         * parenthesised calls. A `modifier` is almost always `Modifier.fillMaxWidth()` or a chain of
         * those, so a pattern that stops at the first parenthesis stops at almost every real call.
         *
         * Whitespace belongs in the value too, or a modifier chain the formatter has split across
         * lines ends the argument early and the literal after it is missed.
         *
         * A quote can appear in neither branch. That is deliberate: it is what stops the scan running
         * past its own argument and reporting a string from somewhere further down the file.
         */
        private const val ARGUMENT_VALUE = """(?:[\w.\s]|\([^"()]*\))+"""

        /**
         * Slots that put text in front of a user. `\s*` around the argument is what lets a wrapped
         * call be caught, and the optional `text =` catches the named form.
         */
        private val USER_FACING_SLOTS = listOf(
            Regex("""\bText\(\s*(?:\w+\s*=\s*$ARGUMENT_VALUE\s*,\s*)*(?:text\s*=\s*)?"([^"]{2,})""""),
            Regex("""\blabel\s*=\s*"([^"]{2,})""""),
            Regex("""\bcontentDescription\s*=\s*"([^"]{2,})""""),
            Regex("""\bplaceholder\s*=\s*"([^"]{2,})""""),
            Regex("""\bheadline\s*=\s*"([^"]{2,})""""),
            Regex("""\bsupporting\s*=\s*"([^"]{2,})""""),
            Regex("""\bmessage\s*=\s*"([^"]{2,})""""),
            Regex("""\bactionLabel\s*=\s*"([^"]{2,})""""),
            Regex("""\btitle\s*=\s*"([^"]{2,})""""),
        )

        private val PREVIEW_MARKERS = listOf(
            "accountName =", "amountDisplay =", "destAmountDisplay =", "nativeDisplay =",
            "baseDisplay =", "totalDisplay =", "incomeDisplay =", "expenseDisplay =", "netDisplay =",
            "CategoryLabel.Named(", "Account(", "Category(", "Currency(", "Money.of(",
            "NetWorthState.Amount(", "CategoryRow(",
        )
    }
}
