package org.aristonis.mywallet.ui.settings

internal sealed interface LicenseBlock {
    data class Title(val lines: List<String>) : LicenseBlock
    data class Heading(val text: String) : LicenseBlock
    data class Paragraph(val text: String) : LicenseBlock
    data class ListItem(val marker: String, val text: String) : LicenseBlock
}

/** Removes source-file wrapping while retaining every piece of license content for display. */
internal fun formatLicenseText(raw: String): List<LicenseBlock> = formatLines(raw.lines(), 0, emptyList())

private tailrec fun formatLines(
    lines: List<String>,
    index: Int,
    blocks: List<LicenseBlock>,
): List<LicenseBlock> {
    if (index >= lines.size) return blocks
    if (lines[index].isBlank()) return formatLines(lines, index + 1, blocks)

    if (blocks.isEmpty() && isTitleLine(lines[index])) {
        val titleEnd = nextBlankLine(lines, index)
        val title = LicenseBlock.Title(lines.subList(index, titleEnd).map { it.trim() })
        return formatLines(lines, titleEnd, blocks + title)
    }

    val heading = headingAt(lines[index])
    if (heading != null) {
        val paragraph = wrappedTextAfter(lines, index + 1, heading.remainingText)
        val updatedBlocks = blocks + LicenseBlock.Heading(heading.text) + paragraph.toParagraph()
        return formatLines(lines, paragraph?.nextIndex ?: index + 1, updatedBlocks)
    }

    val listMatch = LIST_ITEM_PATTERN.matchEntire(lines[index])
    if (listMatch != null) {
        val item = wrappedTextAfter(lines, index + 1, listMatch.groupValues[2])
            ?: WrappedText(text = "", nextIndex = index + 1)
        val block = LicenseBlock.ListItem(marker = listMatch.groupValues[1], text = item.text)
        return formatLines(lines, item.nextIndex, blocks + block)
    }

    val paragraph = requireNotNull(wrappedTextAfter(lines, index + 1, lines[index].trim()))
    return formatLines(lines, paragraph.nextIndex, blocks + LicenseBlock.Paragraph(paragraph.text))
}

/** Returns URL character ranges without sentence punctuation that belongs to surrounding prose. */
internal fun findUrls(text: String): List<IntRange> = URL_PATTERN.findAll(text).map { match ->
    val urlEnd = match.value.indexOfLast { it !in URL_TRAILING_PUNCTUATION }
    match.range.first..(match.range.first + urlEnd)
}.toList()

private fun isTitleLine(line: String): Boolean = line.takeWhile(Char::isWhitespace).length >= TITLE_INDENTATION

private fun nextBlankLine(lines: List<String>, startIndex: Int): Int {
    val relativeBlankLine = lines.subList(startIndex, lines.size).indexOfFirst(String::isBlank)
    return if (relativeBlankLine == NO_INDEX) lines.size else startIndex + relativeBlankLine
}

private fun headingAt(line: String): HeadingLine? {
    val trimmed = line.trim()
    val numbered = NUMBERED_HEADING_PATTERN.matchEntire(trimmed)
    if (numbered != null) {
        return HeadingLine(text = numbered.groupValues[1], remainingText = numbered.groupValues[2])
    }

    return when {
        trimmed.startsWith(APPENDIX_PREFIX) -> HeadingLine(text = trimmed, remainingText = "")
        trimmed.any(Char::isLetter) && trimmed == trimmed.uppercase() -> HeadingLine(text = trimmed, remainingText = "")
        else -> null
    }
}

private fun wrappedTextAfter(
    lines: List<String>,
    startIndex: Int,
    firstLine: String,
): WrappedText? {
    if (firstLine.isBlank() && (startIndex >= lines.size || endsWrappedText(lines[startIndex]))) return null

    val relativeEnd = lines.subList(startIndex, lines.size).indexOfFirst(::endsWrappedText)
    val endIndex = if (relativeEnd == NO_INDEX) lines.size else startIndex + relativeEnd

    val text = listOf(firstLine)
        .plus(lines.subList(startIndex, endIndex))
        .filter(String::isNotBlank)
        .joinToString(" ") { it.trim() }
    return WrappedText(text = text, nextIndex = endIndex)
}

private fun endsWrappedText(line: String): Boolean =
    line.isBlank() || headingAt(line) != null || LIST_ITEM_PATTERN.matches(line)

private data class HeadingLine(val text: String, val remainingText: String)

private data class WrappedText(val text: String, val nextIndex: Int)

private fun WrappedText?.toParagraph(): List<LicenseBlock.Paragraph> =
    listOfNotNull(this?.let { LicenseBlock.Paragraph(it.text) })

private const val TITLE_INDENTATION = 4
private const val APPENDIX_PREFIX = "APPENDIX:"
private const val URL_TRAILING_PUNCTUATION = ".,;:!?)]}"
private const val NO_INDEX = -1

private val NUMBERED_HEADING_PATTERN = Regex("^(\\d+\\.\\s+.+?\\.)(?:\\s+(.*))?$")
private val LIST_ITEM_PATTERN = Regex("^\\s*(\\([a-z]\\))\\s*(.*)$")
private val URL_PATTERN = Regex("https?://\\S+")
