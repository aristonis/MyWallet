package org.aristonis.mywallet.ui

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.aristonis.mywallet.data.csv.RfcCsvEncoder
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.data.repo.RoomAccountRepository
import org.aristonis.mywallet.data.repo.RoomCategoryRepository
import org.aristonis.mywallet.data.repo.RoomCategoryRoleRepository
import org.aristonis.mywallet.data.repo.RoomCsvExportSource
import org.aristonis.mywallet.data.repo.RoomCurrencyRepository
import org.aristonis.mywallet.data.repo.RoomDebtRepository
import org.aristonis.mywallet.data.repo.RoomEntryRepository
import org.aristonis.mywallet.data.repo.RoomFxRepository
import org.aristonis.mywallet.data.repo.RoomLedgerQueries
import org.aristonis.mywallet.data.repo.RoomSettingsRepository
import org.aristonis.mywallet.data.repo.RoomTransactionRunner
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.aristonis.mywallet.domain.usecase.ComputeHomeSummary
import org.aristonis.mywallet.domain.usecase.CreateCategory
import org.aristonis.mywallet.domain.usecase.DeleteAccount
import org.aristonis.mywallet.domain.usecase.DeleteCategory
import org.aristonis.mywallet.domain.usecase.ExportTrackingCsv
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import org.aristonis.mywallet.domain.usecase.ObserveCategoryUsage
import org.aristonis.mywallet.domain.usecase.ObserveDebts
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.domain.usecase.RenameCategory
import org.aristonis.mywallet.domain.usecase.SetAccountArchived
import org.aristonis.mywallet.domain.usecase.UpdateAccount
import org.aristonis.mywallet.ui.account.EditAccountViewModel
import org.aristonis.mywallet.ui.account.ManageAccountsViewModel
import org.aristonis.mywallet.ui.category.ManageCategoriesViewModel
import org.aristonis.mywallet.ui.debt.DebtsViewModel
import org.aristonis.mywallet.ui.debt.TodaySource
import org.aristonis.mywallet.ui.home.HomeViewModel
import org.aristonis.mywallet.ui.reports.ReportsViewModel
import org.aristonis.mywallet.ui.settings.AndroidDocumentIo
import org.aristonis.mywallet.ui.transaction.TransactionsListViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Hand-wired app dependencies over Room; failed health reads are never replaced by test flows. */
internal class UnreadableScreenDatabase(kind: String) : AutoCloseable {
    val healthReads = AtomicInteger()
    val reported = ConcurrentLinkedQueue<Throwable>()
    private val date = LocalDate.of(2026, 10, 12)
    val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryCallback(RoomDatabase.QueryCallback { query, _ ->
            if (query.contains("FROM entries WHERE kind NOT IN")) healthReads.incrementAndGet()
        }, Executor { it.run() })
        .build()
    private val accounts = RoomAccountRepository(db.accountDao())
    private val categories = RoomCategoryRepository(db.categoryDao())
    private val currencies = RoomCurrencyRepository(db.currencyDao())
    private val ledger = RoomLedgerQueries(db.ledgerQueryDao())
    private val fx = RoomFxRepository(db)
    private val health = ObserveLedgerHealth(ledger)
    private val errors = ErrorReporter { _, failure -> reported.add(failure) }
    private val formatter = MoneyFormatter(Locale.US)
    private val settings = RoomSettingsRepository(db.settingsDao())
    private val sql get() = db.openHelper.writableDatabase

    init {
        sql.execSQL("INSERT INTO currencies (code, symbol, decimalPlaces) VALUES ('USD', '$', 2)")
        sql.execSQL(
            "INSERT INTO accounts (id, name, typeKey, currencyCode, openingBalanceAmount, archived, sortOrder) " +
                "VALUES (1, 'Cash', 'cash', 'USD', '100', 0, 0), (2, 'Unused', 'cash', 'USD', '0', 0, 1)",
        )
        sql.execSQL("INSERT INTO categories (id, name, kind, parentId) VALUES (1, 'Salary', 'INCOME', 0)")
        sql.execSQL("INSERT INTO entries (id, kind, date, note) VALUES (7, ?, ?, 'Repaired income')",
            arrayOf<Any>(kind, date.toEpochDay()))
        runBlocking { settings.save(Settings("USD", schemaVersion = 3)) }
    }

    /** Repair the same header and give it the legs a real income recipe reads. */
    fun repair() {
        db.runInTransaction {
            sql.execSQL("UPDATE entries SET kind = 'INCOME' WHERE id = 7")
            sql.execSQL(
                "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) " +
                    "VALUES (7, 'ACCOUNT', 0, 1, '1', 'USD')",
            )
            sql.execSQL(
                "INSERT INTO entry_legs (entryId, role, position, categoryId, amount, currencyCode) " +
                    "VALUES (7, 'MAIN', 0, 1, '1', 'USD')",
            )
        }
    }

    fun home() = HomeViewModel(GetAccountBalances(accounts, ledger), GetAccountBalancesInBase(fx),
        ComputeHomeSummary(ledger, fx), health, currencies, settings, formatter, Dispatchers.Default, errors)

    fun transactions() = TransactionsListViewModel(RoomEntryRepository(db, db.entryDao()), accounts, categories,
        currencies, settings, health, formatter, TodayProvider { date }, SavedStateHandle(),
        Dispatchers.Default, errors)

    fun tracking() = ReportsViewModel(ComputeCategoryBreakdown(ledger, fx), health, categories, currencies, formatter,
        TodayProvider { date }, SavedStateHandle(), Dispatchers.Default, errors,
        ExportTrackingCsv(RoomCsvExportSource(db, fx), RfcCsvEncoder()),
        AndroidDocumentIo(ApplicationProvider.getApplicationContext()))

    fun debts() = DebtsViewModel(ObserveDebts(RoomDebtRepository(db), ledger, fx, flowOf(date)), health, currencies,
        formatter, TodaySource { Clock.fixed(date.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC) },
        Dispatchers.Default, errors)

    fun categories() = ManageCategoriesViewModel(categories, ObserveCategoryUsage(ledger), health,
        CreateCategory(categories, RoomCategoryRoleRepository(db.categoryRoleDao()), RoomTransactionRunner(db)),
        RenameCategory(categories), DeleteCategory(categories), errors)

    fun editAccount() = EditAccountViewModel(accounts, currencies, ledger, health,
        UpdateAccount(currencies, accounts, ledger), MoneyParser(Locale.US), errors)

    fun deleteAccount() = ManageAccountsViewModel(accounts, ledger, currencies, formatter, Dispatchers.Default,
        SetAccountArchived(accounts), DeleteAccount(accounts, ledger), health, errors)

    fun unusedAccountExists(): Boolean = sql.query("SELECT id FROM accounts WHERE id = 2").use { it.moveToFirst() }

    override fun close() = db.close()
}
