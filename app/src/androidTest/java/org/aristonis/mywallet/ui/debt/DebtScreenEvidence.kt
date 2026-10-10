package org.aristonis.mywallet.ui.debt

import android.graphics.Bitmap
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.io.File

internal fun ComposeContentTestRule.reachDebtRow(text: String) {
    val lists = onAllNodes(hasScrollToNodeAction())
    lists.fetchSemanticsNodes().indices.forEach { index ->
        runCatching { lists[index].performScrollToNode(hasText(text, substring = true)) }.onSuccess { return }
    }
    error("no scrollable debt list reached $text")
}

internal fun ComposeContentTestRule.debtScreenshot(name: String) {
    waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    instrumentation.uiAutomation.waitForIdle(500, 5_000)
    val directory = File(instrumentation.targetContext.filesDir, "wave6-evidence").apply { mkdirs() }
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    File(directory, "$name.png").outputStream().use { output ->
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
    }
    bitmap.recycle()
}
