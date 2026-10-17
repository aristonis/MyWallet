package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class IncomeRowLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun salaryTitleFitsOneLineWithThreeDeductionsAndFee() {
        showSalary(fontScale = 1f)

        val title = compose.onNodeWithText("Salary", useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        title.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals("The income title must retain its width", 1, layouts.single().lineCount)
        assertFalse(layouts.single().hasVisualOverflow)
        assertDetailsBelowTitle()
        val amount = compose.onNodeWithText("845.00 USD", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("The receipt amount belongs on the trailing side", amount.left >= title.boundsInRoot.right)
    }

    @Test
    fun largeTextKeepsDetailsUnderTitleAndStacksOnlyAmount() {
        showSalary(fontScale = 2f)

        assertDetailsBelowTitle()
        val fee = compose.onNodeWithText("Fee", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val amount = compose.onNodeWithText("845.00 USD", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Only the amount moves below supporting details", amount.top >= fee.bottom)
    }

    private fun assertDetailsBelowTitle() {
        val title = compose.onNodeWithText("Salary", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("incomeViewport").fetchSemanticsNode().boundsInRoot
        listOf("Account receives", "Paid now", "Owed later", "Fee").forEach { label ->
            val node = compose.onNodeWithText(label, substring = true, useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode()
            assertTrue("$label must be below the title", node.boundsInRoot.top >= title.bottom)
            assertTrue("$label must share the title column", node.boundsInRoot.left == title.left)
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertFalse("$label must remain complete", layouts.single().hasVisualOverflow)
            assertTrue(node.boundsInRoot.right <= viewport.right)
        }
    }

    private fun showSalary(fontScale: Float) {
        val deductions = listOf(
            Triple("Tax", DeductionTiming.NOW, "100"),
            Triple("Bills", DeductionTiming.NOW, "50"),
            Triple("Savings", DeductionTiming.LATER, "20"),
        ).map { (name, timing, amount) ->
            TransactionDeductionRow(name, timing, Money.of(amount, "USD"), "$amount.00 USD", null)
        }
        val row = TransactionRow(
            id = 1L,
            date = LocalDate.of(2026, 10, 17),
            type = TransactionRowType.INCOME,
            accountName = "Cash",
            destAccountName = null,
            categoryLabel = CategoryLabel.Named("Salary"),
            amount = Money.of("1000", "USD"),
            destAmount = null,
            amountDisplay = "1,000.00 USD",
            destAmountDisplay = null,
            note = null,
            grossAmountDisplay = "1,000.00 USD",
            accountMovementDisplay = "845.00 USD",
            deductions = deductions,
            deductionSummaries = listOf(
                TransactionDeductionSummary(DeductionTiming.NOW, Money.of("150", "USD"), "150.00 USD", 2),
                TransactionDeductionSummary(DeductionTiming.LATER, Money.of("20", "USD"), "20.00 USD", 1),
            ),
            feeDisplay = "5.00 USD",
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).testTag("incomeViewport")) {
                        TransactionRowItem(row, onClick = {})
                    }
                }
            }
        }
    }
}
