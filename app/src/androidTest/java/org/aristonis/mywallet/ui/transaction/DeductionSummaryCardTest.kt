package org.aristonis.mywallet.ui.transaction

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The income summary names gross, total deductions, the account receipt and the new debt, each value
 * under its own label; the deductions section offers a retry only for a failed catalog.
 */
@RunWith(AndroidJUnit4::class)
class DeductionSummaryCardTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val currencies = listOf(Currency("USD", "$", 2))
    private val formatter = MoneyFormatter(context.resources.configuration.locales[0])

    private fun text(id: Int) = context.getString(id)

    private fun shown(amount: String) = bidiIsolate(formatter.display(Money.of(amount, "USD"), currencies))

    /** Salary 1000: Tax 100 later, Family 50 later, Bills 20 now; the account gets 980, 150 is owed. */
    private val salary = IncomeDeductionSummary(
        gross = Money.of("1000.00", "USD"),
        deductions = Money.of("170.00", "USD"),
        accountReceives = Money.of("980.00", "USD"),
        owed = Money.of("150.00", "USD"),
    )

    private val labelsInOrder = listOf(
        R.string.income_summary_gross,
        R.string.income_summary_deductions,
        R.string.income_summary_receives,
        R.string.income_summary_owed,
    )

    /** Some node showing [value] sits below the label and above the next label in the card. */
    private fun assertValueUnderLabel(labelId: Int, value: String) {
        val label = compose.onNodeWithText(text(labelId)).assertIsDisplayed().getUnclippedBoundsInRoot()
        val nextIndex = labelsInOrder.indexOf(labelId) + 1
        val nextTop = labelsInOrder.getOrNull(nextIndex)
            ?.let { compose.onNodeWithText(text(it)).getUnclippedBoundsInRoot().top }
        val values = compose.onAllNodesWithText(value).fetchSemanticsNodes()
        val beside = values.any { node ->
            val top = with(compose.density) { node.boundsInRoot.top.toDp() }
            top >= label.bottom && (nextTop == null || top < nextTop)
        }
        assertTrue("${text(labelId)} should be followed by $value", beside)
    }

    private fun showCard(summary: IncomeDeductionSummary) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) { DeductionSummaryCard(summary, currencies) }
        }
    }

    private fun assertSalaryValues() {
        assertValueUnderLabel(R.string.income_summary_gross, shown("1000.00"))
        assertValueUnderLabel(R.string.income_summary_deductions, shown("170.00"))
        assertValueUnderLabel(R.string.income_summary_receives, shown("980.00"))
        assertValueUnderLabel(R.string.income_summary_owed, shown("150.00"))
    }

    @Test
    fun salarySummaryShowsEachTotalUnderItsLabel() {
        showCard(salary)

        assertSalaryValues()
    }

    @Test
    fun accountReceivingZeroIsShownAsAValidTotal() {
        showCard(
            IncomeDeductionSummary(
                gross = Money.of("100.00", "USD"),
                deductions = Money.of("100.00", "USD"),
                accountReceives = Money.zero("USD"),
                owed = Money.zero("USD"),
            ),
        )

        compose.onAllNodesWithText(shown("0.00")).assertCountEquals(2)
        assertValueUnderLabel(R.string.income_summary_receives, shown("0.00"))
        assertValueUnderLabel(R.string.income_summary_owed, shown("0.00"))
    }

    @Test
    fun summaryIsReadableInDarkRtlAtTwoHundredPercentFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(darkTheme = true, dynamicColor = false) {
                    DeductionSummaryCard(salary, currencies)
                }
            }
        }

        assertSalaryValues()
        listOf("1000.00", "170.00", "980.00", "150.00").forEach {
            compose.onNodeWithText(shown(it)).assertIsDisplayed()
        }
    }

    private fun loadedState(vararg drafts: IncomeDeductionDraft) = IncomeDeductionFormState(
        itemsLoaded = true,
        itemsLoading = false,
        drafts = drafts.toList(),
    )

    private fun showSection(
        state: IncomeDeductionFormState,
        onEdit: (Long, DeductionEdit) -> Unit = { _, _ -> },
        onRetry: () -> Unit = {},
    ) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DeductionsSection(state, currencies, "USD", onEdit, onRetry)
            }
        }
    }

    @Test
    fun failedCatalogOffersRetryThatAsksForAnotherRead() {
        var retries = 0
        showSection(
            IncomeDeductionFormState(itemsLoading = false, catalogError = UiMessage.DeductionItemNotFound),
            onRetry = { retries++ },
        )

        compose.onNodeWithText(text(R.string.action_retry)).assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun loadedCatalogOffersNoRetry() {
        showSection(loadedState(draft(7L, "Tax")))

        compose.onNodeWithText(text(R.string.action_retry)).assertDoesNotExist()
    }

    @Test
    fun untickingADeductionSendsADeselectForThatRow() {
        val edits = mutableListOf<Pair<Long, DeductionEdit>>()
        showSection(
            loadedState(draft(7L, "Tax"), draft(9L, "Bills")),
            onEdit = { id, edit -> edits += id to edit },
        )

        compose.onNode(isToggleable() and hasText(bidiIsolate("Bills"))).performClick()

        compose.runOnIdle {
            assertEquals(listOf(9L to DeductionEdit.SetSelected(false)), edits)
        }
    }

    private fun draft(id: Long, name: String) = IncomeDeductionDraft(
        itemId = id,
        name = name,
        categoryId = id,
        mode = DeductionMode.PERCENT,
        valueInput = "10",
        valueCurrencyCode = null,
        timing = DeductionTiming.LATER,
        selected = true,
    )
}
