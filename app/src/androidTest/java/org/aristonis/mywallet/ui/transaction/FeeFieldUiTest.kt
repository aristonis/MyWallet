package org.aristonis.mywallet.ui.transaction

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime checks for fee controls and the amounts labelled as received or paid. */
@RunWith(AndroidJUnit4::class)
class FeeFieldUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val currencies = listOf(Currency("USD", "$", 2))
    private val formatter = MoneyFormatter(context.resources.configuration.locales[0])
    private fun text(id: Int) = context.getString(id)
    private fun shown(value: String) = bidiIsolate(formatter.display(Money.of(value, "USD"), currencies))

    private fun show(
        state: FeeFormState,
        type: TransactionType = TransactionType.TRANSFER,
        largeRtl: Boolean = false,
        onValue: (String) -> Unit = {},
        onCategory: (Long?) -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, if (largeRtl) 2f else density.fontScale),
            ) {
                MyWalletTheme(darkTheme = largeRtl, dynamicColor = false) {
                    FeeField(state, type, "Cash", "USD", currencies,
                        listOf(Category(4, "Food", CategoryKind.EXPENSE)), onValue, {}, onCategory, {})
                }
            }
        }
    }

    @Test fun transferShowsDestinationReceiptAndAllowsSettingZero() {
        var typed: String? = null
        show(FeeFormState(FeeMode.PERCENT, "2", accountMovement = Money.of("294", "USD")), onValue = { typed = it })
        val preview = context.getString(R.string.fee_transfer_receives, bidiIsolate("Cash"), shown("294"))
        compose.onNodeWithText(preview).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.fee_percent_value)).performTextReplacement("0")
        compose.runOnIdle { assertEquals("0", typed) }
    }

    @Test fun expenseShowsTotalAccountPayment() {
        show(FeeFormState(FeeMode.FIXED, "1", accountMovement = Money.of("51", "USD")), TransactionType.EXPENSE)
        compose.onNodeWithText(context.getString(R.string.fee_expense_pays, shown("51"))).assertIsDisplayed()
    }

    @Test fun missingRoleOffersUncategorizedAndExpensePicker() {
        var picked: Long? = null
        show(FeeFormState(FeeMode.PERCENT, "2", needsCategoryPicker = true), onCategory = { picked = it })
        compose.onNode(hasText(text(R.string.fee_uncategorized)) and hasClickAction()).performClick()
        compose.onNodeWithText(bidiIsolate("Food")).performClick()
        compose.runOnIdle { assertEquals(4L, picked) }
    }

    @Test fun previewRemainsReadableInDarkRtlAtTwoHundredPercentFont() {
        show(FeeFormState(FeeMode.PERCENT, "2", accountMovement = Money.of("294", "USD")), largeRtl = true)
        compose.onNodeWithText(context.getString(R.string.fee_transfer_receives,
            bidiIsolate("Cash"), shown("294"))).assertIsDisplayed()
    }
    @Test fun incomeShowsFeeAndAccountReceipt() {
        show(FeeFormState(FeeMode.PERCENT, "2", accountMovement = Money.of("980", "USD")),
            type = TransactionType.INCOME)
        compose.onNodeWithText(text(R.string.fee_income_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.fee_income_receives, shown("980")))
            .assertIsDisplayed()
    }

}
