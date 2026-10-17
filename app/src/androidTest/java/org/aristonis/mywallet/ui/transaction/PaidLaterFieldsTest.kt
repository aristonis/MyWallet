package org.aristonis.mywallet.ui.transaction

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaidLaterFieldsTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun enabledSwitchShowsPaidNowNameAndOptionalDateInRtl() {
        var paid: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyWalletTheme(darkTheme = false, dynamicColor = false) {
                    PaidLaterFields(PaidLaterFormState(enabled = true, paidNowInput = "300",
                        owed = Money.of("700", "USD")), emptyList(), "USD", listOf(Currency("USD", "$", 2)),
                        {}, { paid = it }, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.paid_later_paid_now)).assertIsDisplayed()
            .performTextReplacement("400")
        compose.onNodeWithText(context.getString(R.string.kind_counterparty)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.paid_later_due_date)).assertIsDisplayed()
        compose.runOnIdle { assertEquals("400", paid) }
    }
}
