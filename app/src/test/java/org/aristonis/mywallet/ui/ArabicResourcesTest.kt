package org.aristonis.mywallet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class ArabicResourcesTest {
    private val english by lazy { resources("values") }
    private val arabic by lazy { resources("values-ar") }

    @Test
    fun everyTranslatableStringAndPluralHasAnArabicResource() {
        val expected = english.filterValues { it.getAttribute("translatable") != "false" }
        assertTrue("Arabic resources must exist", arabic.isNotEmpty())
        assertEquals("Arabic resource names", expected.keys, arabic.keys)
        expected.forEach { (name, source) ->
            assertEquals("Resource type for $name", source.tagName, arabic.getValue(name).tagName)
        }
    }

    @Test
    fun everyArabicPluralHasAllSixArabicQuantities() {
        val expectedQuantities = setOf("zero", "one", "two", "few", "many", "other")
        val expectedNames = english.filterValues { it.tagName == "plurals" }.keys
        assertTrue("English plural resources must exist", expectedNames.isNotEmpty())
        expectedNames.forEach { name ->
            val translated = requireNotNull(arabic[name]) { "Missing Arabic plural $name" }
            val items = children(translated, "item")
            assertEquals("Quantities for $name", expectedQuantities, items.map { it.getAttribute("quantity") }.toSet())
            assertEquals("No duplicate quantities for $name", expectedQuantities.size, items.size)
        }
    }

    @Test
    fun arabicFormatArgumentsMatchTheEnglishContract() {
        english.filterValues { it.getAttribute("translatable") != "false" }.forEach { (name, source) ->
            val translated = requireNotNull(arabic[name]) { "Missing Arabic resource $name" }
            if (source.tagName == "plurals") {
                val sourceItems = children(source, "item").associateBy { it.getAttribute("quantity") }
                children(translated, "item").forEach { item ->
                    val quantity = item.getAttribute("quantity")
                    val original = sourceItems[quantity] ?: sourceItems.getValue("other")
                    assertEquals("Arguments for $name/$quantity", arguments(original), arguments(item))
                }
            } else {
                assertEquals("Arguments for $name", arguments(source), arguments(translated))
            }
        }
    }

    @Test
    fun nonTranslatableResourcesAreNotCopiedIntoArabic() {
        english.filterValues { it.getAttribute("translatable") == "false" }.keys.forEach { name ->
            assertFalse("Non-translatable resource $name must not be copied", name in arabic)
        }
    }

    private fun resources(directory: String): Map<String, Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val elements = File("src/main/res/$directory").listFiles().orEmpty()
            .filter { it.name.startsWith("strings") && it.extension == "xml" }.sortedBy { it.name }
            .flatMap { file ->
                val root = factory.newDocumentBuilder().parse(file).documentElement
                children(root).filter { it.tagName in setOf("string", "plurals") }
            }
        val grouped = elements.groupBy { it.getAttribute("name") }
        assertTrue("Resource names must be unique in $directory", grouped.values.all { it.size == 1 })
        return elements.associateBy { it.getAttribute("name") }
    }

    private fun children(parent: Element, tag: String? = null): List<Element> =
        (0 until parent.childNodes.length).mapNotNull { parent.childNodes.item(it) as? Element }
            .filter { tag == null || it.tagName == tag }

    private fun arguments(element: Element): List<String> {
        if (element.getAttribute("formatted") == "false") return emptyList()
        val pattern = Regex("%(?:(\\d+)\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?([a-zA-Z%])")
        var nextIndex = 1
        return pattern.findAll(element.textContent).mapNotNull { match ->
            val type = match.groupValues[2]
            if (type == "%" || type == "n") null else {
                val index = match.groupValues[1].ifEmpty { (nextIndex++).toString() }
                "$index:$type"
            }
        }.sorted().toList()
    }
}
