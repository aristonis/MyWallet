package org.aristonis.mywallet.ui.category

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.UnreadableScreenDatabase
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryNamePromptTest {
    @get:Rule val compose = createComposeRule()

    private val store = ViewModelStore()
    private var database: UnreadableScreenDatabase? = null

    @After fun tearDown() {
        compose.runOnIdle { store.clear() }
        database?.close()
    }

    @Test fun duplicateAddKeepsTypedNameAndShowsRefusalInsideDialog() {
        val room = UnreadableScreenDatabase("INCOME").also {
            database = it
            it.repair()
        }
        val viewModel = room.categories()
        store.put("categories", viewModel)
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                val state by viewModel.state.collectAsState()
                ManageCategoriesContent(state, viewModel::create, viewModel::rename, viewModel::delete,
                    viewModel::acknowledge, viewModel::affectedBy, {}, viewModel::retry)
            }
        }
        compose.waitUntil(10_000) { !viewModel.state.value.isLoading && !viewModel.state.value.loadFailed }

        // Salary already exists in the Income group of the in-memory ledger.
        compose.onAllNodesWithText(text(R.string.action_add))[1].performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Salary")
        compose.onNode(hasText(text(R.string.action_add)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { viewModel.state.value.error != null }

        compose.onNodeWithText(text(R.string.category_new)).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Salary")).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.msg_duplicate_category_name, "Salary")) and
            hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    private fun text(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
}
