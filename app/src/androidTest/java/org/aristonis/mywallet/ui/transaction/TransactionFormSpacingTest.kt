package org.aristonis.mywallet.ui.transaction

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionFormSpacingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun amountErrorUsesRelatedFieldGap() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                AmountField(
                    value = "",
                    label = "Amount",
                    onValueChange = {},
                    errorText = "Enter an amount",
                )
            }
        }

        val fieldBottom = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.bottom
        val errorTop = compose.onNodeWithText("Enter an amount").fetchSemanticsNode().boundsInRoot.top
        val requiredGapPx = 8f * compose.density.density
        assertTrue("Amount error needs the signed 8dp related-field gap", errorTop - fieldBottom >= requiredGapPx - 1f)
    }
}
