package org.aristonis.mywallet.ui.currency

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaseCurrencyRequiredRatePlacementTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectingLastUnratedCurrencyRevealsItsRateAboveTheLongListAtLargeText() {
        val currencies = (0 until 30).map { index ->
            Currency("C${index.toString().padStart(2, '0')}", "¤", 2)
        }
        var state by mutableStateOf(ChangeBaseCurrencyUiState(currentBase = "USD", selectable = currencies))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).height(640.dp)) {
                        ChangeBaseCurrencyContent(
                            state = state,
                            onSelect = { state = state.copy(selectedCode = it, needsRate = true) },
                            onRateChanged = { state = state.copy(rateInput = it) },
                            onApply = {},
                            onDone = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("base-currency-options")
            .performScrollToNode(hasTestTag("select-currency-C29"))
        compose.onNodeWithTag("select-currency-C29").performClick()

        compose.onNodeWithTag("base-required-rate").assertIsDisplayed().performTextInput("1.25")
        compose.onNodeWithText("Switch to C29").assertIsDisplayed()
    }
}
