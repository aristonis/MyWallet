package org.aristonis.mywallet.domain.model

import org.aristonis.mywallet.domain.error.WalletException
import java.text.Normalizer
import java.util.Locale

/**
 * The form of a typed name that decides "same person", so " Ali ", "ALI" and "Ali" pasted with an
 * invisible direction mark all find one debt instead of opening three. The name as typed is kept
 * elsewhere for display; this key is only ever compared.
 *
 * The key is stored as the identity of each debt and of each deduction item, so it is frozen once
 * the app ships it. Changing any step below makes stored keys stop matching the names typed from
 * then on, and one person splits into two debts. Such a change needs a migration that re-keys the
 * stored rows and merges the debts that collide under the new key.
 *
 * The steps, in order:
 * 1. NFKC, so a precomposed and a combining accent meet, and odd spaces and Arabic presentation
 *    forms become their plain letters.
 * 2. Invisible format characters (direction marks, zero-width space, byte order mark) are dropped.
 * 3. Arabic tatweel and short vowel marks are dropped, and alef with hamza, alef maksura and teh
 *    marbuta fold to bare alef, yeh and heh. This is a product choice: people spell names casually,
 *    and the same person should be found however the ending or hamza was typed.
 * 4. Whitespace runs of any kind collapse to one space, then the ends are trimmed.
 * 5. NFKC again: a character dropped in steps 2 and 3 can have sat between a letter and its
 *    accent, and without this pass the stored key would key differently when typed back in.
 * 6. Lower-casing with [Locale.ROOT], never the device locale. On a Turkish device the default turns
 *    "I" into a dotless "ı", so "ALI" would key apart from "Ali".
 *
 * Accepted limits, all because full case folding needs ICU, which would be a new dependency:
 * "ß" and "SS" stay two people; capital dotted "İ" lowers to "i" plus a combining dot, so it stays
 * apart from a plain "i"; and a Greek name typed in lower case with a plain sigma at the end stays
 * apart from its upper-case form, which lowers to a final sigma.
 */
@JvmInline
value class NameKey private constructor(val value: String) {

    companion object {
        private const val ARABIC_TATWEEL = '\u0640'
        private const val ARABIC_MARKS_FIRST = '\u064B'
        private const val ARABIC_MARKS_LAST = '\u065F'
        private const val ARABIC_SUPERSCRIPT_ALEF = '\u0670'

        private const val ALEF = '\u0627'
        private const val ALEF_WITH_HAMZA_ABOVE = '\u0623'
        private const val ALEF_WITH_HAMZA_BELOW = '\u0625'
        private const val ALEF_WITH_MADDA_ABOVE = '\u0622'
        private const val ALEF_MAKSURA = '\u0649'
        private const val YEH = '\u064A'
        private const val TEH_MARBUTA = '\u0629'
        private const val HEH = '\u0647'

        /** Arabic letters people type interchangeably in names, mapped to the one the key keeps. */
        private val ARABIC_LETTER_FOLDS: Map<Char, Char> = mapOf(
            ALEF_WITH_HAMZA_ABOVE to ALEF,
            ALEF_WITH_HAMZA_BELOW to ALEF,
            ALEF_WITH_MADDA_ABOVE to ALEF,
            ALEF_MAKSURA to YEH,
            TEH_MARBUTA to HEH,
        )

        /**
         * The key for [name].
         *
         * @throws WalletException.NameBlank when nothing is left once invisible characters,
         *   decoration and spaces are removed.
         */
        fun of(name: String): NameKey {
            val normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
            val cleaned = collapseWhitespace(foldArabic(dropFormatCharacters(normalized)))
            val folded = Normalizer.normalize(cleaned, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            if (folded.isEmpty()) throw WalletException.NameBlank()
            return NameKey(folded)
        }

        /** Walks code points, not chars, so a format character outside the BMP is dropped whole. */
        private fun dropFormatCharacters(text: String): String {
            val kept = StringBuilder(text.length)
            text.codePoints()
                .filter { Character.getType(it) != Character.FORMAT.toInt() }
                .forEach { kept.appendCodePoint(it) }
            return kept.toString()
        }

        private fun foldArabic(text: String): String = buildString(text.length) {
            for (c in text) {
                if (!isArabicDecoration(c)) append(ARABIC_LETTER_FOLDS[c] ?: c)
            }
        }

        private fun isArabicDecoration(c: Char): Boolean =
            c == ARABIC_TATWEEL || c in ARABIC_MARKS_FIRST..ARABIC_MARKS_LAST || c == ARABIC_SUPERSCRIPT_ALEF

        /** Covers tabs and line breaks as well as no-break and ideographic spaces. */
        private fun collapseWhitespace(text: String): String = buildString(text.length) {
            var inRun = false
            for (c in text) {
                val isSpace = Character.isWhitespace(c) || Character.isSpaceChar(c)
                if (!isSpace) append(c) else if (!inRun) append(' ')
                inRun = isSpace
            }
        }.trim()
    }
}
