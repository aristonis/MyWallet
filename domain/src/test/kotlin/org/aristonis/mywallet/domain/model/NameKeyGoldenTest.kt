package org.aristonis.mywallet.domain.model

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Golden vectors for [NameKey]. These strings are stored as debt keys, so if any vector changes,
 * debts already on disk stop matching the names typed today and one person splits into two debts.
 * A change to any expected value here therefore needs a re-key migration of the stored keys.
 *
 * Every non-ASCII or invisible character is written as a \u escape so the file reads the same in
 * any editor.
 */
class NameKeyGoldenTest {

    private fun key(name: String) = NameKey.of(name).value

    // Padding and case: the basic "same person" rule.
    @Test
    fun paddedAndMixedCaseNameKeysAsLowerCase() {
        assertEquals("ali", key(" Ali "))
    }

    // Inner whitespace runs, tabs and single spaces must land on one key.
    @Test
    fun innerWhitespaceRunsCollapseToOneSpace() {
        assertEquals("ali hassan", key("Ali  Hassan"))
        assertEquals("ali hassan", key("Ali\tHassan"))
        assertEquals("ali hassan", key("Ali Hassan"))
    }

    // NBSP (U+00A0) and ideographic space (U+3000) become plain spaces under NFKC.
    @Test
    fun noBreakAndIdeographicSpacesCountAsSpaces() {
        assertEquals("ali hassan", key("Ali\u00A0Hassan"))
        assertEquals("ali hassan", key("Ali\u3000\u00A0 Hassan"))
    }

    // Precomposed and combining forms of the same letter must meet (NFKC composes).
    @Test
    fun composedAndDecomposedAccentsGiveTheSameKey() {
        assertEquals("jos\u00E9", key("Jos\u00E9"))
        assertEquals("jos\u00E9", key("Jose\u0301"))
    }

    // Invisible direction marks pasted from chat apps must not split a person.
    @Test
    fun invisibleFormatCharactersAreRemoved() {
        assertEquals("ali", key("\u200FAli\u200E"))
        assertEquals("ali", key("A\u200Bli"))
        assertEquals("ali", key("\uFEFFAli"))
        assertEquals("ali", key("\u202AAl\u202Ci\u2066\u2069"))
    }

    // Arabic presentation forms (U+FEE3 U+FEA4 U+FEE4 U+FEAA) fold to the base letters under NFKC.
    @Test
    fun arabicPresentationFormsFoldToBaseLetters() {
        assertEquals("\u0645\u062D\u0645\u062F", key("\uFEE3\uFEA4\uFEE4\uFEAA"))
    }

    // Alef with hamza above, below, or none are one name.
    @Test
    fun alefVariantsFoldToBareAlef() {
        assertEquals("\u0627\u062D\u0645\u062F", key("\u0623\u062D\u0645\u062F"))
        assertEquals("\u0627\u062D\u0645\u062F", key("\u0625\u062D\u0645\u062F"))
        assertEquals("\u0627\u062D\u0645\u062F", key("\u0627\u062D\u0645\u062F"))
        assertEquals("\u0627\u0628", key("\u0622\u0628"))
    }

    // Teh marbuta and heh are typed interchangeably at the end of names.
    @Test
    fun tehMarbutaFoldsToHeh() {
        assertEquals("\u0641\u0627\u0637\u0645\u0647", key("\u0641\u0627\u0637\u0645\u0629"))
        assertEquals("\u0641\u0627\u0637\u0645\u0647", key("\u0641\u0627\u0637\u0645\u0647"))
    }

    // Alef maksura and yeh are typed interchangeably at the end of names.
    @Test
    fun alefMaksuraFoldsToYeh() {
        assertEquals("\u0639\u0644\u064A", key("\u0639\u0644\u0649"))
        assertEquals("\u0639\u0644\u064A", key("\u0639\u0644\u064A"))
    }

    // Tatweel stretching and vowel marks (fatha U+064E, shadda U+0651) are decoration, not identity.
    @Test
    fun tatweelAndShortVowelMarksAreRemoved() {
        assertEquals("\u0645\u062D\u0645\u062F", key("\u0645\u0640\u062D\u064E\u0645\u0651\u062F"))
        // Superscript alef U+0670 and the end of the mark range U+065F.
        assertEquals("\u0645\u062D", key("\u0645\u0670\u062D\u065F"))
    }

    // Accepted limit: ss is not folded to sharp s, so these stay two people.
    @Test
    fun sharpSAndDoubleSStayApart() {
        assertNotEquals(NameKey.of("Stra\u00DFe"), NameKey.of("STRASSE"))
        assertNotEquals(NameKey.of("Strau\u00DF"), NameKey.of("STRAUSS"))
    }

    // Accepted limit: capital dotted I lowercases to i + combining dot above under Locale.ROOT,
    // so it stays apart from plain "ismail".
    @Test
    fun dottedCapitalIKeysAsIWithCombiningDot() {
        assertEquals("i\u0307smail", key("\u0130smail"))
        assertNotEquals(NameKey.of("\u0130smail"), NameKey.of("ismail"))
    }

    // Greek capital sigma at the end of a word lowercases to final sigma (U+03C2), so upper and
    // lower case spellings written with a final sigma meet.
    @Test
    fun greekUpperAndLowerCaseWithFinalSigmaGiveEqualKeys() {
        val upper = "\u039F\u0394\u03A5\u03A3\u03A3\u0395\u03A5\u03A3"
        val lower = "\u03BF\u03B4\u03C5\u03C3\u03C3\u03B5\u03C5\u03C2"
        assertEquals("\u03BF\u03B4\u03C5\u03C3\u03C3\u03B5\u03C5\u03C2", key(upper))
        assertEquals(NameKey.of(upper), NameKey.of(lower))
    }

    // Accepted limit: a lower-case name typed with a plain sigma at the end does not meet the
    // upper-case form, because only the latter lowercases to final sigma.
    @Test
    fun greekPlainFinalSigmaStaysApartFromFinalSigmaForm() {
        val upper = "\u039F\u0394\u03A5\u03A3\u03A3\u0395\u03A5\u03A3"
        val plainSigma = "\u03BF\u03B4\u03C5\u03C3\u03C3\u03B5\u03C5\u03C3"
        assertNotEquals(NameKey.of(upper), NameKey.of(plainSigma))
    }

    // A name that is only invisible marks has no identity and must be refused.
    @Test
    fun onlyInvisibleMarksIsBlank() {
        assertThrows(WalletException.NameBlank::class.java) { NameKey.of("\u200B\u200F\uFEFF\u2066\u2069") }
    }

    // A name that is only tatweel (and marks) has no identity and must be refused.
    @Test
    fun onlyTatweelIsBlank() {
        assertThrows(WalletException.NameBlank::class.java) { NameKey.of("\u0640\u0640") }
        assertThrows(WalletException.NameBlank::class.java) { NameKey.of("\u0640\u064E\u0651") }
    }

    // Spaces of every kind, alone, are blank.
    @Test
    fun onlySpacesIsBlank() {
        assertThrows(WalletException.NameBlank::class.java) { NameKey.of("   ") }
        assertThrows(WalletException.NameBlank::class.java) { NameKey.of("\u00A0\u3000\t") }
    }

    // A dropped invisible mark must not leave a letter and its accent apart: keying the stored key
    // again would compose them and give a second key for the same person.
    @Test
    fun aDroppedMarkBetweenALetterAndItsAccentStillComposes() {
        assertEquals("\u00E1", key("a\u200C\u0301"))
        assertEquals("jos\u00E9", key("Jose\u00AD\u0301"))
    }

    // A stored key typed back in must give itself, or a restore or a retyped name splits the debt.
    @Test
    fun keyingAKeyGivesTheSameKey() {
        listOf(
            " Ali ", "Ali\u3000\u00A0 Hassan", "Jose\u0301", "a\u200C\u0301", "Jose\u00AD\u0301",
            "\u202AAl\u202Ci\u2066\u2069", "\uFEE3\uFEA4\uFEE4\uFEAA", "\u0623\u062D\u0645\u062F",
            "\u0641\u0627\u0637\u0645\u0629", "\u0639\u0644\u0649", "\u0645\u0640\u062D\u064E\u0645\u0651\u062F",
            "Strau\u00DF", "\u0130smail", "\u039F\u0394\u03A5\u03A3\u03A3\u0395\u03A5\u03A3",
        ).forEach { name -> assertEquals(name, key(name), key(key(name))) }
    }
}
