package org.aristonis.mywallet.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AristonisLoaderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun loadingHasOneStableIndeterminateStatusAndNoDecorativeTextAnnouncements() {
        compose.setContent { MyWalletTheme { AristonisLoader() } }
        compose.onAllNodesWithContentDescription("Loading").assertCountEquals(1)
        compose.onNodeWithContentDescription("Loading").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        )
        compose.onNodeWithText("Excellence is a habit").assertDoesNotExist()
    }

    @Test
    fun completionRemovesStatusWithoutWaitingForRevealOrCycle() {
        val isLoading = mutableStateOf(true)
        compose.setContent { MyWalletTheme { if (isLoading.value) AristonisLoader() } }
        compose.onNodeWithContentDescription("Loading").assertExists()
        compose.runOnIdle { isLoading.value = false }
        compose.onNodeWithContentDescription("Loading").assertDoesNotExist()
    }

}
