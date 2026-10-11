package org.aristonis.mywallet.ui.debt

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.aristonis.mywallet.data.db.EntryEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.repo.RoomAccountRepository
import org.aristonis.mywallet.data.repo.RoomCategoryRepository
import org.aristonis.mywallet.data.repo.RoomCategoryRoleRepository
import org.aristonis.mywallet.data.repo.RoomCurrencyRepository
import org.aristonis.mywallet.data.repo.RoomDebtRepository
import org.aristonis.mywallet.data.repo.RoomEntryRepository
import org.aristonis.mywallet.data.repo.RoomFxRepository
import org.aristonis.mywallet.data.repo.RoomLedgerQueries
import org.aristonis.mywallet.data.repo.RoomRateRepository
import org.aristonis.mywallet.data.repo.RoomSettingsRepository
import org.aristonis.mywallet.data.repo.RoomTransactionRunner
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.RoleCategories
import org.aristonis.mywallet.domain.usecase.DeleteDebt
import org.aristonis.mywallet.domain.usecase.ObserveDebt
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.domain.usecase.PreviewDebtDeletion
import org.aristonis.mywallet.domain.usecase.RenameDebt
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import org.aristonis.mywallet.domain.usecase.UpdateDebtDetails
import org.aristonis.mywallet.domain.usecase.WriteOffDebt
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class DebtDeletionRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: WalletDatabase
    private lateinit var entries: RoomEntryRepository
    private lateinit var vm: DebtDetailViewModel
    private lateinit var opening: Transaction.OwedBefore
    private var debtId = 0L
    private val day = LocalDate.of(2026, 10, 12)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java).build()
        val debts = RoomDebtRepository(db)
        entries = RoomEntryRepository(db, db.entryDao())
        debtId = debts.getOrCreate("Ali", DebtSide.I_OWE, "USD").id
        val draft = Transaction.OwedBefore(debtId = debtId, side = DebtSide.I_OWE,
            amount = Money.of("100", "USD"), date = day, note = "Original loan")
        opening = draft.copy(id = entries.insert(draft))
        vm = detailViewModel(debts)
    }

    @After fun tearDown() {
        if (::vm.isInitialized) compose.runOnIdle { vm.viewModelScope.cancel() }
        if (::db.isInitialized) db.close()
    }

    @Test fun undoReappearsAfterAnUnreadableWalletIsRepairedAndRestoresTheActualEntry() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DebtDetailScreen(debtId, onDone = {}, onEditEntry = {}, viewModel = vm)
            }
        }
        compose.waitUntil(WAIT_MS) { vm.state.value.detail != null && !vm.state.value.isLoading }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete debt").performClick()
        compose.waitUntil(WAIT_MS) { vm.state.value.deleteDialog != null && !vm.state.value.isSubmitting }
        compose.onNode(hasText("Delete debt") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(WAIT_MS) { vm.state.value.undoableDelete != null && !vm.state.value.isSubmitting }
        compose.onNodeWithText("Undo").assertIsDisplayed()
        runBlocking { assertNull(entries.findById(opening.id)) }

        val badId = runBlocking { db.entryDao().insertEntry(EntryEntity(kind = "UNKNOWN", date = day)) }
        compose.waitUntil(WAIT_MS) { vm.state.value.loadFailed }
        compose.onNodeWithText("Undo").assertDoesNotExist()

        runBlocking { db.entryDao().deleteById(badId) }
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(WAIT_MS) { !vm.state.value.loadFailed && !vm.state.value.isLoading }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Undo")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Undo").assertIsDisplayed().performClick()
        compose.waitUntil(WAIT_MS) { vm.state.value.undoableDelete == null && !vm.state.value.isSubmitting }
        runBlocking { assertEquals(opening, entries.findById(opening.id)) }
    }

    @Test fun dismissingTheSuccessfulDeleteSnackbarFinishesTheScreenExactlyOnce() {
        var doneCalls = 0
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DebtDetailScreen(debtId, onDone = { doneCalls++ }, onEditEntry = {}, viewModel = vm)
            }
        }
        compose.waitUntil(WAIT_MS) { vm.state.value.detail != null && !vm.state.value.isLoading }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete debt").performClick()
        compose.waitUntil(WAIT_MS) { vm.state.value.deleteDialog != null && !vm.state.value.isSubmitting }
        compose.onNode(hasText("Delete debt") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(WAIT_MS) { vm.state.value.undoableDelete != null && !vm.state.value.isSubmitting }
        compose.onNodeWithText("Debt deleted").assertIsDisplayed()

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { dismiss -> dismiss() }

        compose.waitUntil(WAIT_MS) { vm.state.value.deleted }
        compose.runOnIdle {
            assertEquals(1, doneCalls)
            assertNull(vm.state.value.undoableDelete)
        }
        compose.onNodeWithText("Undo").assertDoesNotExist()
        runBlocking { assertNull(entries.findById(opening.id)) }
    }

    private fun detailViewModel(debts: RoomDebtRepository): DebtDetailViewModel {
        val runner = RoomTransactionRunner(db)
        val ledger = RoomLedgerQueries(db.ledgerQueryDao())
        val accounts = RoomAccountRepository(db.accountDao())
        val categories = RoomCategoryRepository(db.categoryDao())
        val currencies = RoomCurrencyRepository(db.currencyDao())
        val roles = RoomCategoryRoleRepository(db.categoryRoleDao())
        val settings = RoomSettingsRepository(db.settingsDao())
        val rates = RoomRateRepository(db.rateDao())
        val save = SaveEntry(runner, accounts, categories, entries, RoomFxRepository(db), settings,
            SetExchangeRate(currencies, rates, ledger, runner, settings), debts, ledger, RoleCategories(roles, categories))
        val today = TodaySource { Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC) }
        return DebtDetailViewModel(ObserveDebt(debts, ledger, entries, today.dates), RenameDebt(runner, debts),
            UpdateDebtDetails(runner, debts), currencies, MoneyFormatter(Locale.US), today, Dispatchers.Default,
            ErrorReporter { _, _ -> }, ObserveLedgerHealth(ledger), categories, roles,
            WriteOffDebt(runner, debts, ledger, save), PreviewDebtDeletion(runner, debts, entries, ledger),
            DeleteDebt(runner, debts, entries, ledger), RestoreEntries(runner, entries, categories, accounts, debts, ledger))
    }

    private companion object { const val WAIT_MS = 5_000L }
}
