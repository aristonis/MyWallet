package org.aristonis.mywallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.ui.account.EditAccountScreen
import org.aristonis.mywallet.ui.account.ManageAccountsContent
import org.aristonis.mywallet.ui.category.ManageCategoriesContent
import org.aristonis.mywallet.ui.debt.DebtsContent
import org.aristonis.mywallet.ui.home.HomeContent
import org.aristonis.mywallet.ui.reports.ReportsExportState
import org.aristonis.mywallet.ui.reports.ReportsContent
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.transaction.TransactionsListContent
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Stored unreadable kinds reach every affected screen, and Retry reads the repaired Room row. */
@RunWith(AndroidJUnit4::class)
class UnreadableRowScreensTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private var database: UnreadableScreenDatabase? = null
    private enum class Screen { HOME, TRANSACTIONS, TRACKING, DEBTS, CATEGORIES, ACCOUNT_EDIT, ACCOUNT_DELETE }

    @After fun tearDown() {
        compose.runOnIdle { store.clear() }
        database?.close()
    }

    @Test fun unknownKindOnHome() = verify(Screen.HOME, "FROM_THE_FUTURE")
    @Test fun unknownKindOnTransactions() = verify(Screen.TRANSACTIONS, "FROM_THE_FUTURE")
    @Test fun unknownKindOnTracking() = verify(Screen.TRACKING, "FROM_THE_FUTURE")
    @Test fun unknownKindOnDebts() = verify(Screen.DEBTS, "FROM_THE_FUTURE")
    @Test fun unknownKindOnCategories() = verify(Screen.CATEGORIES, "FROM_THE_FUTURE")
    @Test fun unknownKindOnAccountEdit() = verify(Screen.ACCOUNT_EDIT, "FROM_THE_FUTURE")
    @Test fun unknownKindOnAccountDelete() = verify(Screen.ACCOUNT_DELETE, "FROM_THE_FUTURE")
    @Test fun legacyUnreadableOnHome() = verify(Screen.HOME, "LEGACY_UNREADABLE")
    @Test fun legacyUnreadableOnTransactions() = verify(Screen.TRANSACTIONS, "LEGACY_UNREADABLE")
    @Test fun legacyUnreadableOnTracking() = verify(Screen.TRACKING, "LEGACY_UNREADABLE")
    @Test fun legacyUnreadableOnDebts() = verify(Screen.DEBTS, "LEGACY_UNREADABLE")
    @Test fun legacyUnreadableOnCategories() = verify(Screen.CATEGORIES, "LEGACY_UNREADABLE")
    @Test fun legacyUnreadableOnAccountEdit() = verify(Screen.ACCOUNT_EDIT, "LEGACY_UNREADABLE")
    @Test fun legacyUnreadableOnAccountDelete() = verify(Screen.ACCOUNT_DELETE, "LEGACY_UNREADABLE")

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun verify(screen: Screen, kind: String) {
        val room = UnreadableScreenDatabase(kind).also { database = it }
        lateinit var failed: () -> Boolean
        lateinit var recovered: () -> Boolean
        lateinit var content: @Composable () -> Unit
        compose.runOnIdle {
            val binding = bind(screen, room)
            store.put("screen", binding.model)
            failed = binding.failed
            recovered = binding.recovered
            content = binding.content
        }
        compose.setContent { MyWalletTheme(dynamicColor = false) { content() } }
        compose.waitUntil(TIMEOUT_MS) { failed() }
        val message = when (screen) {
            Screen.HOME -> R.string.home_data_unreadable
            Screen.TRANSACTIONS -> R.string.transactions_data_unreadable
            Screen.TRACKING -> R.string.tracking_data_unreadable
            else -> R.string.msg_data_unreadable
        }
        compose.onNodeWithText(text(message)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).assertIsDisplayed()
        assertTrue(room.reported.any { it is WalletException.UnreadableRow && it.id == 7L && it.kind == kind })

        room.repair()
        compose.waitForIdle()
        compose.onNodeWithText(text(message)).assertIsDisplayed()
        val readsBeforeRetry = room.healthReads.get()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        compose.waitUntil(TIMEOUT_MS) { recovered() && room.healthReads.get() > readsBeforeRetry }
        compose.onNodeWithText(text(message)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.action_retry)).assertDoesNotExist()
        if (screen == Screen.ACCOUNT_DELETE) deleteUnusedAccount(room)
    }

    private fun deleteUnusedAccount(room: UnreadableScreenDatabase) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.cd_row_actions, "Unused")).performClick()
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        compose.waitUntil(TIMEOUT_MS) { !room.unusedAccountExists() }
        // The row leaves the screen when the next read arrives, a moment after the stored row is gone.
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Unused").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Unused").assertDoesNotExist()
    }

    private data class Binding(
        val model: ViewModel,
        val failed: () -> Boolean,
        val recovered: () -> Boolean,
        val content: @Composable () -> Unit,
    )

    private fun bind(screen: Screen, room: UnreadableScreenDatabase): Binding = when (screen) {
        Screen.HOME -> bindHome(room)
        Screen.TRANSACTIONS -> bindTransactions(room)
        Screen.TRACKING -> bindTracking(room)
        Screen.DEBTS -> bindDebts(room)
        Screen.CATEGORIES -> bindCategories(room)
        Screen.ACCOUNT_EDIT -> bindAccountEdit(room)
        Screen.ACCOUNT_DELETE -> bindAccountDelete(room)
    }

    private fun bindHome(room: UnreadableScreenDatabase): Binding {
        val vm = room.home()
        return Binding(vm, { vm.state.value.loadFailed }, { !vm.state.value.isLoading && !vm.state.value.loadFailed }) {
            val state by vm.state.collectAsState()
            HomeContent(state, {}, {}, {}, {}, {}, {}, vm::retry)
        }
    }

    private fun bindTransactions(room: UnreadableScreenDatabase): Binding {
        val vm = room.transactions()
        return Binding(vm, { vm.state.value.loadFailed }, { !vm.state.value.isLoading && !vm.state.value.loadFailed }) {
            val state by vm.state.collectAsState()
            TransactionsListContent(state, vm.windowActions, {}, {}, {}, {}, vm::retry)
        }
    }

    private fun bindTracking(room: UnreadableScreenDatabase): Binding {
        val vm = room.tracking()
        return Binding(vm, { vm.state.value.loadFailed }, { !vm.state.value.loadFailed &&
            vm.state.value.data !is org.aristonis.mywallet.ui.reports.ReportsData.Loading }) {
            val state by vm.state.collectAsState()
            ReportsContent(state, vm.windowActions, vm::retry, ReportsExportState.Idle, {}, {})
        }
    }

    private fun bindDebts(room: UnreadableScreenDatabase): Binding {
        val vm = room.debts()
        return Binding(vm, { vm.state.value.loadFailed }, { !vm.state.value.isLoading && !vm.state.value.loadFailed }) {
            val state by vm.state.collectAsState()
            DebtsContent(state, {}, {}, {}, vm::selectSide, vm::retry, {})
        }
    }

    private fun bindCategories(room: UnreadableScreenDatabase): Binding {
        val vm = room.categories()
        return Binding(vm, { vm.state.value.loadFailed }, { !vm.state.value.isLoading && !vm.state.value.loadFailed }) {
            val state by vm.state.collectAsState()
            ManageCategoriesContent(state, vm::create, vm::rename, vm::delete, vm::acknowledge,
                vm::affectedBy, {}, vm::retry)
        }
    }

    private fun bindAccountEdit(room: UnreadableScreenDatabase): Binding {
        val vm = room.editAccount()
        return Binding(vm, { vm.state.value.loadFailed }, { vm.state.value.loaded?.id == 1L }) {
            EditAccountScreen(1, {}, vm)
        }
    }

    private fun bindAccountDelete(room: UnreadableScreenDatabase): Binding {
        val vm = room.deleteAccount()
        return Binding(vm, { vm.state.value.loadFailed }, { !vm.state.value.isLoading && !vm.state.value.loadFailed }) {
            val state by vm.state.collectAsState()
            ManageAccountsContent(state, vm::archive, vm::unarchive, vm::delete, {}, {}, {}, vm::retry)
        }
    }

    private companion object { const val TIMEOUT_MS = 10_000L }
}
