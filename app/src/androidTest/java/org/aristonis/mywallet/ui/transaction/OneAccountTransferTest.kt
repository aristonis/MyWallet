package org.aristonis.mywallet.ui.transaction

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OneAccountTransferTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun oneAccountTransferExplainsWhyNoDestinationCanBeChosen() {
        val form = TransactionFormHarness(TransactionType.TRANSFER)
        form.change { it.copy(accounts = it.accounts.take(1), selectedAccountId = 1, destAccountId = null) }

        compose.setContent { MyWalletTheme(dynamicColor = false) { form.Content() } }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.transfer_add_second_account)).assertIsDisplayed()
        val prompt = context.getString(R.string.value_select_prompt)
        compose.onNodeWithText(prompt).assertDoesNotExist()
    }

    @Test
    fun zeroAccountTransferExplainsThatAnAccountIsNeeded() {
        val form = TransactionFormHarness(TransactionType.TRANSFER)
        form.change { it.copy(accounts = emptyList(), selectedAccountId = null, destAccountId = null) }

        compose.setContent { MyWalletTheme(dynamicColor = false) { form.Content() } }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.transfer_add_first_account)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.field_to_account)).assertDoesNotExist()
    }
}
