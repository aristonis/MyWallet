package org.aristonis.mywallet.ui.deduction

import androidx.lifecycle.SavedStateHandle
import java.math.BigDecimal
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.model.*
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.usecase.SaveDeductionItem
import org.aristonis.mywallet.domain.usecase.CreateCategory
import org.aristonis.mywallet.domain.usecase.SaveDeductionDefinition
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.transaction.FakeCurrencyRepository
import org.aristonis.mywallet.ui.transaction.FakeSettingsRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeductionEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    private fun editor(
        items: DeductionSettingsFake,
        id: Long = 0,
        locale: Locale = Locale.US,
        reporter: ErrorReporter = ErrorReporter { _, _ -> },
        categories: DeductionCategories = DeductionCategories(listOf(
            Category(1, "Bills", CategoryKind.EXPENSE), Category(2, "Salary", CategoryKind.INCOME))),
    ): DeductionEditorViewModel {
        val runner = DeductionRunner(categories, items)
        return DeductionEditorViewModel(
            SavedStateHandle(mapOf("id" to id)), items, categories,
            FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))),
            FakeSettingsRepository(Settings("EUR")),
            SaveDeductionDefinition(items, categories, CreateCategory(categories, DeductionRoles(), runner),
                SaveDeductionItem(items), runner),
            MoneyParser(locale), reporter,
        )
    }

    private fun categoryHierarchy() = DeductionCategories(listOf(
        Category(1, "Bills", CategoryKind.EXPENSE),
        Category(3, "Internet", CategoryKind.EXPENSE, parentId = 1),
        Category(4, "Phone", CategoryKind.EXPENSE, parentId = 1),
        Category(5, "Other", CategoryKind.EXPENSE),
        Category(2, "Salary", CategoryKind.INCOME)))

    @Test fun pickingParentOffersOnlyItsChildrenAndChangingParentClearsChild() = runTest {
        val vm = editor(DeductionSettingsFake(), categories = categoryHierarchy())
        advanceUntilIdle()

        vm.selectExpenseCategory(1)
        assertEquals(listOf(3L, 4L), vm.state.value.expenseSubCategories.map { it.id })
        vm.selectExpenseSubCategory(3)
        vm.selectExpenseCategory(5)

        assertNull(vm.state.value.expenseSubCategoryId)
        assertTrue(vm.state.value.expenseSubCategories.isEmpty())
    }

    @Test fun legacyChildSelectionLoadsAsParentAndChildWithoutMissingNotice() = runTest {
        val vm = editor(DeductionSettingsFake(listOf(rule().copy(expenseCategoryId = 3))),
            id = 1, categories = categoryHierarchy())

        advanceUntilIdle()

        assertEquals(1L, vm.state.value.expenseCategoryId)
        assertEquals(3L, vm.state.value.expenseSubCategoryId)
        assertEquals(listOf(3L, 4L), vm.state.value.expenseSubCategories.map { it.id })
        assertFalse(vm.state.value.expenseCategoryMissing)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test fun savingChildStoresChildIdAndClearingItStoresParentId() = runTest {
        val items = DeductionSettingsFake(listOf(rule()))
        val categories = categoryHierarchy()
        val vm = editor(items, id = 1, categories = categories)
        advanceUntilIdle()
        vm.selectExpenseSubCategory(3)

        vm.submit()
        advanceUntilIdle()

        assertTrue(vm.state.value.saved)
        assertEquals(3L, items.rows.value.single().expenseCategoryId)
        val edit = editor(items, id = 1, categories = categories)
        advanceUntilIdle()
        edit.selectExpenseSubCategory(null)
        edit.submit()
        advanceUntilIdle()
        assertTrue(edit.state.value.saved)
        assertEquals(1L, items.rows.value.single().expenseCategoryId)
    }

    @Test fun deletedSelectedChildRequiresAnExplicitChoice() = runTest {
        val categories = categoryHierarchy()
        val vm = editor(DeductionSettingsFake(listOf(rule().copy(expenseCategoryId = 3))),
            id = 1, categories = categories)
        advanceUntilIdle()

        categories.rows.value = categories.rows.value.filter { it.id != 3L }
        advanceUntilIdle()

        assertTrue(vm.state.value.expenseCategoryMissing)
        assertFalse(vm.state.value.canSubmit)
        vm.selectExpenseCategory(1)
        assertTrue(vm.state.value.canSubmit)
        assertNull(vm.state.value.expenseSubCategoryId)
    }

    @Test fun firstExpenseListContainsOnlyTopLevelCategories() = runTest {
        val categories = DeductionCategories(listOf(
            Category(1, "Bills", CategoryKind.EXPENSE),
            Category(3, "Internet", CategoryKind.EXPENSE, parentId = 1),
            Category(2, "Salary", CategoryKind.INCOME)))
        val vm = editor(DeductionSettingsFake(), categories = categories)

        advanceUntilIdle()

        assertEquals(listOf(1L), vm.state.value.expenseCategories.map { it.id })
    }

    @Test fun categoryEmissionPrunesDeletedAndInvalidAutoAddSelections() = runTest {
        val items = DeductionSettingsFake(listOf(rule().copy(autoAddCategoryIds = setOf(2, 3, 4, 5))))
        val categories = DeductionCategories(listOf(
            Category(1, "Bills", CategoryKind.EXPENSE), Category(2, "Salary", CategoryKind.INCOME),
            Category(3, "Bonus", CategoryKind.INCOME), Category(4, "Other", CategoryKind.INCOME),
            Category(5, "Extra", CategoryKind.INCOME)))
        val vm = editor(items, 1, categories = categories)
        advanceUntilIdle()
        assertEquals(setOf(2L, 3L, 4L, 5L), vm.state.value.autoAddCategoryIds)
        categories.rows.value = listOf(Category(1, "Bills", CategoryKind.EXPENSE),
            Category(2, "Salary", CategoryKind.INCOME), Category(4, "Other", CategoryKind.EXPENSE),
            Category(5, "Extra", CategoryKind.INCOME, parentId = 2))
        advanceUntilIdle()
        assertEquals(setOf(2L), vm.state.value.autoAddCategoryIds)
    }

    @Test fun deletedOrWrongKindExpenseSelectionAsksForAChoiceInsteadOfAutomatic() = runTest {
        val categories = DeductionCategories(listOf(Category(1, "Bills", CategoryKind.EXPENSE)))
        val vm = editor(DeductionSettingsFake(listOf(rule())), 1, categories = categories)
        advanceUntilIdle()
        assertEquals(1L, vm.state.value.expenseCategoryId)
        assertFalse(vm.state.value.expenseCategoryMissing)
        categories.rows.value = emptyList()
        advanceUntilIdle()
        assertNull(vm.state.value.expenseCategoryId)
        assertTrue(vm.state.value.expenseCategoryMissing)
        assertFalse(vm.state.value.canSubmit)
        categories.rows.value = listOf(Category(3, "Other", CategoryKind.EXPENSE))
        advanceUntilIdle()
        vm.selectExpenseCategory(3)
        assertFalse(vm.state.value.expenseCategoryMissing)
        categories.rows.value = listOf(Category(3, "Other", CategoryKind.INCOME))
        advanceUntilIdle()
        assertNull(vm.state.value.expenseCategoryId)
        assertTrue(vm.state.value.expenseCategoryMissing)
    }

    @Test fun ruleOpenedAfterItsCategoryWasDeletedSavesOnlyAfterTheOwnerConfirmsAutomatic() = runTest {
        val items = DeductionSettingsFake(listOf(rule()))
        val categories = DeductionCategories(listOf(Category(2, "Salary", CategoryKind.INCOME)))
        val vm = editor(items, 1, categories = categories)
        advanceUntilIdle()
        assertTrue(vm.state.value.expenseCategoryMissing)
        assertNull(vm.state.value.expenseCategoryId)
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.state.value.saved)
        assertEquals(1, categories.rows.value.size)
        vm.selectExpenseCategory(null)
        assertFalse(vm.state.value.expenseCategoryMissing)
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        val created = categories.rows.value.single { it.kind == CategoryKind.EXPENSE }
        assertEquals("Tax", created.name)
        assertEquals(created.id, items.rows.value.single().expenseCategoryId)
    }

    @Test fun deletedRuleIdShowsNotFoundWithoutRetry() = runTest {
        val reports = mutableListOf<Throwable>()
        val vm = editor(DeductionSettingsFake(), 5, reporter = ErrorReporter { _, error -> reports.add(error) })
        advanceUntilIdle()
        assertTrue(vm.state.value.notFound)
        assertFalse(vm.state.value.loadFailed)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.canSubmit)
        assertTrue(reports.isEmpty())
    }

    @Test fun unrelatedCategoryEmissionsKeepValidSelections() = runTest {
        val categories = DeductionCategories(listOf(Category(1, "Bills", CategoryKind.EXPENSE),
            Category(2, "Salary", CategoryKind.INCOME)))
        val vm = editor(DeductionSettingsFake(listOf(rule())), 1, categories = categories)
        advanceUntilIdle()
        categories.rows.value = categories.rows.value + Category(3, "Other", CategoryKind.EXPENSE)
        advanceUntilIdle()
        assertEquals(1L, vm.state.value.expenseCategoryId)
        assertEquals(setOf(2L), vm.state.value.autoAddCategoryIds)
    }

    @Test fun categoryDeletedAfterSnapshotIsTypedRefusalWithoutWrite() = runTest {
        val items = DeductionSettingsFake(listOf(rule()))
        val categories = DeductionCategories(listOf(Category(1, "Bills", CategoryKind.EXPENSE),
            Category(2, "Salary", CategoryKind.INCOME)))
        val vm = editor(items, 1, categories = categories)
        advanceUntilIdle()
        categories.hiddenMissingIds = setOf(2)
        vm.setValue("12")
        vm.submit()
        advanceUntilIdle()
        assertEquals(UiMessage.CategoryNotFound, vm.state.value.error)
        assertFalse(vm.state.value.saved)
        assertEquals(BigDecimal.TEN, items.rows.value.single().value)
    }

    @Test fun omittedExpenseCategoryCreatesNamedDefaultAndReusesIt() = runTest {
        val items = DeductionSettingsFake()
        val categories = DeductionCategories(emptyList())
        val vm = editor(items, categories = categories)
        advanceUntilIdle()
        vm.setName("Bill")
        vm.setValue("5")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        assertEquals("Bill", categories.rows.value.single().name)
        assertEquals(categories.rows.value.single().id, items.rows.value.single().expenseCategoryId)
        val edit = editor(items, 1, categories = categories)
        advanceUntilIdle()
        edit.selectExpenseCategory(null)
        edit.setValue("7")
        edit.submit()
        advanceUntilIdle()
        assertEquals(1, categories.rows.value.size)
    }

    @Test fun refusedItemSaveRollsBackAutomaticCategoryCreation() = runTest {
        val items = DeductionSettingsFake().apply { saveFailure = WalletException.DeductionItemNameTaken("Tax") }
        val categories = DeductionCategories(emptyList())
        val vm = editor(items, categories = categories)
        advanceUntilIdle()
        vm.setName("Tax")
        vm.setValue("10")
        vm.submit()
        advanceUntilIdle()
        assertEquals(UiMessage.DeductionItemNameTaken, vm.state.value.nameError)
        assertTrue(categories.rows.value.isEmpty())
        assertTrue(items.rows.value.isEmpty())
    }

    @Test fun unchangedLocalizedEditRoundTripsExactValue() = runTest {
        val items = DeductionSettingsFake(listOf(rule().copy(value = BigDecimal("12.5"))))
        val vm = editor(items, 1, Locale.FRANCE)
        advanceUntilIdle()
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        assertEquals(BigDecimal("12.5"), items.rows.value.single().value)
    }

    @Test fun invisibleOnlyNameIsRefusedAtNameField() = runTest {
        val items = DeductionSettingsFake()
        val vm = editor(items)
        advanceUntilIdle()
        vm.setName("\u200e\u200f")
        vm.setValue("5")
        vm.submit()
        advanceUntilIdle()
        assertNotNull(vm.state.value.nameError)
        assertTrue(items.rows.value.isEmpty())
    }

    @Test fun unexpectedSaveIsReportedAndAutomaticCategoryRollsBack() = runTest {
        val failure = IllegalStateException("private diagnostic")
        val items = DeductionSettingsFake().apply { saveFailure = failure }
        val categories = DeductionCategories(emptyList())
        val reports = mutableListOf<Throwable>()
        val vm = editor(items, reporter = ErrorReporter { _, error -> reports.add(error) }, categories = categories)
        advanceUntilIdle()
        vm.setName("Tax")
        vm.setValue("10")
        vm.submit()
        advanceUntilIdle()
        assertEquals(UiMessage.Unexpected, vm.state.value.error)
        assertEquals(listOf(failure), reports)
        assertFalse(vm.state.value.isSubmitting)
        assertTrue(categories.rows.value.isEmpty())
    }

    @Test fun cancelledSaveRollsBackAndIsNotShownAsFailure() = runTest {
        val items = DeductionSettingsFake().apply { saveFailure = CancellationException() }
        val categories = DeductionCategories(emptyList())
        val reports = mutableListOf<Throwable>()
        val vm = editor(items, reporter = ErrorReporter { _, error -> reports.add(error) }, categories = categories)
        advanceUntilIdle()
        vm.setName("Tax")
        vm.setValue("10")
        vm.submit()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.saved)
        assertFalse(vm.state.value.isSubmitting)
        assertTrue(reports.isEmpty())
        assertTrue(categories.rows.value.isEmpty())
    }

    @Test fun failedEditorReadCanBeRetried() = runTest {
        val items = DeductionSettingsFake()
        val failure = IllegalStateException("private diagnostic")
        val categories = DeductionCategories(emptyList()).apply { readFailure = failure }
        val reports = mutableListOf<Throwable>()
        val vm = editor(items, reporter = ErrorReporter { _, error -> reports.add(error) }, categories = categories)
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.canSubmit)
        assertEquals(1, reports.size)
        assertTrue(reports.single() is IllegalStateException)
        assertEquals("private diagnostic", reports.single().message)
        categories.readFailure = null
        vm.retry()
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test fun foldedNameRefusalIsShownAtNameField() = runTest {
        val items = DeductionSettingsFake(listOf(rule()))
        val vm = editor(items)
        advanceUntilIdle()
        vm.setName(" \u200eTAX ")
        vm.setValue("10")
        vm.selectExpenseCategory(1)
        vm.submit()
        advanceUntilIdle()
        assertEquals(UiMessage.DeductionItemNameTaken, vm.state.value.nameError)
        assertFalse(vm.state.value.saved)
        assertEquals(1, items.rows.value.size)
    }

    @Test fun fixedCurrencyDefaultsToBaseAndPercentSavesWithoutCurrency() = runTest {
        val items = DeductionSettingsFake()
        val vm = editor(items)
        advanceUntilIdle()
        assertFalse(vm.state.value.showsCurrency)
        vm.selectMode(DeductionMode.FIXED)
        assertTrue(vm.state.value.showsCurrency)
        assertEquals("EUR", vm.state.value.currencyCode)
        vm.setName("Bill")
        vm.setValue("2.345")
        vm.selectExpenseCategory(1)
        vm.submit()
        advanceUntilIdle()
        assertEquals("EUR", items.rows.value.single().valueCurrencyCode)
        assertEquals(BigDecimal("2.345"), items.rows.value.single().value)
        val percent = editor(items)
        advanceUntilIdle()
        percent.setName("Share")
        percent.setValue("5")
        percent.selectExpenseCategory(1)
        percent.submit()
        advanceUntilIdle()
        assertNull(items.rows.value.last().valueCurrencyCode)
    }

    @Test fun editPreservesIdentityAndRefusesOutOfRangePercentage() = runTest {
        val items = DeductionSettingsFake(listOf(rule()))
        val vm = editor(items, 1)
        advanceUntilIdle()
        assertEquals("Tax", vm.state.value.name)
        vm.setValue("101")
        vm.submit()
        advanceUntilIdle()
        assertEquals(UiMessage.DeductionOutOfRange, vm.state.value.valueError)
        assertEquals(BigDecimal.TEN, items.rows.value.single().value)
        vm.setValue("12.5")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        assertEquals(1L, items.rows.value.single().id)
        assertEquals(BigDecimal("12.5"), items.rows.value.single().value)
    }

    @Test fun blankNameAndNonPositiveValueDoNotWrite() = runTest {
        val items = DeductionSettingsFake()
        val vm = editor(items)
        advanceUntilIdle()
        vm.setValue("0")
        vm.submit()
        advanceUntilIdle()
        assertNotNull(vm.state.value.nameError)
        assertTrue(items.rows.value.isEmpty())
        vm.setName("Bill")
        vm.selectMode(DeductionMode.FIXED)
        vm.selectExpenseCategory(1)
        vm.submit()
        advanceUntilIdle()
        assertEquals(UiMessage.DeductionValueNotPositive, vm.state.value.valueError)
        assertTrue(items.rows.value.isEmpty())
    }
}
