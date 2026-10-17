package org.aristonis.mywallet.ui.components

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class AristonisLoaderResourcesTest {
    @Test
    fun `English loading label and all eight lines match approved copy`() {
        assertCopy("values", "Loading", listOf(
            "Excellence is a habit", "Built, not born", "Code as craft",
            "Excellence, engineered daily", "Elite by choice", "Raising the standard",
            "Measured twice, built once", "Building it right",
        ))
    }

    @Test
    fun `Arabic loading label and all eight lines match approved copy`() {
        assertCopy("values-ar", "جارٍ التحميل", listOf(
            "التميّز عادة", "التميّز يُبنى ولا يولد", "البرمجة حِرفة",
            "تميّز يُصنع كل يوم", "النخبة اختيار", "نرفع المعيار",
            "نقيس مرتين ونبني مرة", "نبنيه كما يجب",
        ))
    }

    private fun assertCopy(folder: String, label: String, lines: List<String>) {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/strings_loader.xml"))
        val strings = document.getElementsByTagName("string")
        assertEquals(1, strings.length)
        assertEquals("aristonis_loader_loading", strings.item(0).attributes.getNamedItem("name").nodeValue)
        assertEquals(label, strings.item(0).textContent)
        val items = document.getElementsByTagName("item")
        assertEquals(lines, (0 until items.length).map { items.item(it).textContent })
    }
}
