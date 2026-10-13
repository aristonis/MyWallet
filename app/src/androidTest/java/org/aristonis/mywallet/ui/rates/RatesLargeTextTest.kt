package org.aristonis.mywallet.ui.rates

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RatesLargeTextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rateInputAndSaveRemainReachableAtTwoHundredPercentInRtl() {
        var saved: String? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.width(360.dp).height(640.dp).testTag("viewport")) {
                        ManageRatesContent(
                            state = ManageRatesUiState(
                                baseCurrencyCode = "USD",
                                isLoading = false,
                                rows = listOf(RateRow("KWD", "د.ك", null, "123456.789")),
                            ),
                            onInputChanged = { _, _ -> },
                            onSave = { saved = it },
                            onDone = {},
                            onRetry = {},
                        )
                    }
                }
            }
        }

        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithText("Save rate").fetchSemanticsNode().boundsInRoot
        assertTrue("Save must stay inside the viewport", save.left >= viewport.left && save.right <= viewport.right)
        compose.onNodeWithText("Save rate").performClick()
        assertEquals("KWD", saved)
    }
}
