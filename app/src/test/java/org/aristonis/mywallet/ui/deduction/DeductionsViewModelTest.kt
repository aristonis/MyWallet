package org.aristonis.mywallet.ui.deduction

import java.math.BigDecimal
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.usecase.*
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeductionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()
    private fun model(
        items: DeductionSettingsFake,
        reporter: ErrorReporter = ErrorReporter { _, _ -> },
        locale: Locale = Locale.US,
    ) = DeductionsViewModel(
        ObserveDeductionItems(items), DeleteDeductionItem(items), SetDeductionItemEnabled(items),
        MoneyParser(locale), reporter,
    )

    @Test fun summaryValueUsesTheLocaleDecimalSeparatorAndKeepsEveryDigit() = runTest {
        val items = DeductionSettingsFake(listOf(rule().copy(value = BigDecimal("12.50")),
            rule().copy(id = 2, name = "Fee", mode = DeductionMode.FIXED, value = BigDecimal("2.345"),
                valueCurrencyCode = "EUR")))
        val vm = model(items, locale = Locale.FRANCE)
        advanceUntilIdle()
        assertEquals(listOf("12,50", "2,345"), vm.state.value.items.map(DeductionListRow::valueText))
        assertEquals(listOf(1L, 2L), vm.state.value.items.map { it.item.id })
    }

    @Test fun failedReadIsReportedAndRetryRecoversWithoutFalseEmptyState() = runTest {
        val failure = IllegalStateException("private diagnostic")
        val items = DeductionSettingsFake(listOf(rule())).apply { readFailure = failure }
        val reports = mutableListOf<Throwable>()
        val vm = model(items, ErrorReporter { _, error -> reports.add(error) })
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.showAddAction)
        assertEquals(listOf(failure), reports)
        items.readFailure = null
        vm.retry()
        advanceUntilIdle()
        assertFalse(vm.state.value.loadFailed)
        assertEquals(1, vm.state.value.items.size)
    }

    @Test fun unexpectedWriteIsReportedWithSafeMessage() = runTest {
        val failure = IllegalStateException("private diagnostic")
        val items = DeductionSettingsFake(listOf(rule())).apply { deleteFailure = failure }
        val reports = mutableListOf<Throwable>()
        val vm = model(items, ErrorReporter { _, error -> reports.add(error) })
        advanceUntilIdle()
        vm.delete(1)
        advanceUntilIdle()
        assertEquals(UiMessage.Unexpected, vm.state.value.error)
        assertEquals(listOf(failure), reports)
        assertEquals(1, vm.state.value.items.size)
        assertFalse(vm.state.value.isWorking)
    }

    @Test fun cancelledWriteIsNotReportedAsFailure() = runTest {
        val items = DeductionSettingsFake(listOf(rule())).apply { deleteFailure = CancellationException() }
        val reports = mutableListOf<Throwable>()
        val vm = model(items, ErrorReporter { _, error -> reports.add(error) })
        advanceUntilIdle()
        vm.delete(1)
        advanceUntilIdle()
        assertTrue(reports.isEmpty())
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isWorking)
        assertEquals(1, vm.state.value.items.size)
    }

    @Test fun emptyListOffersAddAction() = runTest {
        val vm = model(DeductionSettingsFake())
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.showAddAction)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test fun deletingUsedItemOffersDisableAndKeepsHistory() = runTest {
        val items = DeductionSettingsFake(listOf(rule())).apply { used = setOf(1) }
        val vm = model(items)
        advanceUntilIdle()
        vm.delete(1)
        advanceUntilIdle()
        assertEquals(1L, vm.state.value.disableOfferedId)
        assertEquals(1, items.rows.value.size)
        vm.disableUsedItem()
        advanceUntilIdle()
        assertFalse(items.rows.value.single().enabled)
        assertNull(vm.state.value.disableOfferedId)
    }

    @Test fun deletingUnusedItemRemovesIt() = runTest {
        val items = DeductionSettingsFake(listOf(rule()))
        val vm = model(items)
        advanceUntilIdle()
        vm.delete(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.items.isEmpty())
        assertNull(vm.state.value.disableOfferedId)
    }
}
