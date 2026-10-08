package org.aristonis.mywallet.ui.transaction

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Each rate field says which currencies it converts between, so two fields on a cross-currency
 * transfer are never two identical "Rate" boxes to a screen reader, and a rate the app cannot read is
 * marked instead of quietly priced at the saved rate.
 */
@RunWith(AndroidJUnit4::class)
class RateSectionTest {

    @get:Rule val compose = createComposeRule()

    private val fx = FxSnapshot(
        baseCurrencyCode = "SYP",
        ratesToBase = emptyMap(),
        currencies = listOf(Currency("SYP", "£S", 2), Currency("USD", "$", 2), Currency("EUR", "€", 2)),
    )

    private fun show(state: AddTransactionUiState) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) { RateSection(state = state, onRateChanged = { _, _ -> }) }
        }
    }

    private fun transfer(vararg fields: RateField, pairRate: PairRate? = null, received: String? = null) =
        AddTransactionUiState(
            type = TransactionType.TRANSFER,
            date = LocalDate.of(2026, 10, 8),
            fx = fx,
            rateFields = fields.toList(),
            pairRate = pairRate,
            receivedDisplay = received,
        )

    @Test
    fun eachRateFieldIsLabelledWithItsCurrencies() {
        show(transfer(RateField("USD", "4.4", isEditable = true), RateField("EUR", "4", isEditable = true)))

        compose.onNodeWithText("USD rate in SYP").assertIsDisplayed()
        compose.onNodeWithText("EUR rate in SYP").assertIsDisplayed()
    }

    @Test
    fun aCrossTransferSaysWhatTheSourceBuysAndWhatArrives() {
        show(
            transfer(
                RateField("USD", "4.4", isEditable = true),
                RateField("EUR", "4", isEditable = true),
                pairRate = PairRate(from = "USD", to = "EUR", rate = "1.1"),
                received = "110.00 EUR",
            ),
        )

        compose.onNodeWithText("1 USD = 1.1 EUR", substring = true).assertIsDisplayed()
        compose.onNodeWithText("You receive", substring = true).assertIsDisplayed()
    }

    @Test
    fun aReadOnlyRatePointsToWhereItCanBeChanged() {
        show(
            AddTransactionUiState(
                date = LocalDate.of(2026, 10, 8),
                fx = fx,
                rateFields = listOf(RateField("USD", "4", isEditable = false)),
            ),
        )

        compose.onNodeWithText("Currencies & rates", substring = true).assertIsDisplayed()
    }

    @Test
    fun aRateTheAppCannotReadIsMarkedAsAnError() {
        show(transfer(RateField("USD", "4.5", isEditable = true, isError = true)))

        compose.onNodeWithText("USD rate in SYP").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }
}
