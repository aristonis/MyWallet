package org.aristonis.mywallet.ui.debt

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class WriteOffDialogTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 10, 12)
    private val choices = listOf(Category(6, "Lost debt", CategoryKind.EXPENSE),
        Category(7, "Other loss", CategoryKind.EXPENSE))
    private fun draft(category: Long? = null, error: UiMessage? = null) = WriteOffDialogUiState(
        categories = choices, selectedCategoryId = category, date = day, remainingDisplay = "100.00 USD", error = error)

    @Test fun anEmptyCategoryMustBeReplacedBeforeTheWriteOffCanBeConfirmed() {
        val state = mutableStateOf(draft())
        var confirmed: Long? = null
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                WriteOffDialog(state.value, isSubmitting = false,
                    onCategorySelected = { state.value = state.value.copy(selectedCategoryId = it) },
                    onDateSelected = { state.value = state.value.copy(date = it) },
                    onConfirm = { confirmed = state.value.selectedCategoryId }, onDismiss = {}, today = { day })
            }
        }
        compose.onNodeWithText("100.00 USD", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Write off").assertIsNotEnabled()
        compose.onNode(hasText("Select", substring = true) and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText("Other loss") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Write off").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(7L, confirmed) }
    }

    @Test fun aSubmittingWriteOffCannotBeConfirmedOrDismissedTwice() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                WriteOffDialog(draft(6), isSubmitting = true, onCategorySelected = {}, onDateSelected = {},
                    onConfirm = { confirmed++ }, onDismiss = { dismissed++ }, today = { day })
            }
        }
        compose.onNodeWithText("Write off").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, confirmed); assertEquals(0, dismissed) }
    }

    @Test fun aNewRequiredCategoryErrorIsDisplayedAndAnnouncedPolitely() {
        val state = mutableStateOf(draft())
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                WriteOffDialog(state.value, isSubmitting = false, onCategorySelected = {}, onDateSelected = {},
                    onConfirm = {}, onDismiss = {}, today = { day })
            }
        }
        compose.runOnIdle { state.value = state.value.copy(error = UiMessage.CategoryRequired) }
        compose.onNodeWithText("Choose a category.").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }
}
