package org.aristonis.mywallet.ui.reports.charts

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.aristonis.mywallet.domain.model.NoTrendReason
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.window.DateWindowActions
import java.math.BigDecimal
import java.time.LocalDate

// Preview fixtures only. The screens get every value from their view models.

private val PREVIEW_WINDOW = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 10))

private fun spendingRow(id: Long, name: String, amount: String, display: String, parts: List<String> = emptyList()) =
    SpendingRow(id, CategoryLabel.Named(name), BigDecimal(amount), display, parts.map { CategoryLabel.Named(it) })

private val SPENDING_READY = SpendingData.Ready(
    currencyCode = "USD",
    totalDisplay = "419.20 USD",
    rows = listOf(
        spendingRow(1, "Food", "126.40", "126.40 USD", listOf("Groceries", "Dining")),
        spendingRow(2, "Transport", "71.00", "71.00 USD", listOf("Fuel", "Transit")),
        spendingRow(3, "Home", "229.80", "229.80 USD"),
        spendingRow(4, "Clothes", "-8.00", "-8.00 USD"),
    ).sortedByDescending { it.amount },
)

private val SPENDING_LONG = SpendingData.Ready(
    currencyCode = "KWD",
    totalDisplay = "123,456,789.125 KWD",
    rows = listOf(
        spendingRow(1, "Household maintenance and repairs", "123456700.000", "123,456,700.000 KWD", listOf("Plumbing", "Electrical")),
        spendingRow(2, "Transport", "89.125", "89.125 KWD"),
    ),
)

private fun netWorthPoint(day: Int, amount: String) =
    NetWorthPointRow(LocalDate.of(2026, 8, day), BigDecimal(amount), "$amount USD")

private val NET_WORTH_TREND = NetWorthData.Trend(
    currencyCode = "USD",
    points = listOf(
        netWorthPoint(1, "2100.00"), netWorthPoint(2, "2050.00"), netWorthPoint(3, "-150.00"),
        netWorthPoint(4, "2430.00"), netWorthPoint(5, "2430.00"),
    ),
    lowestDisplay = "-150.00 USD",
    highestDisplay = "2430.00 USD",
    direction = TrendDirection.UP,
    crossesZero = true,
)

private val NET_WORTH_LONG = NetWorthData.Trend(
    currencyCode = "KWD",
    points = listOf(
        NetWorthPointRow(LocalDate.of(2026, 8, 1), BigDecimal("123456789.125"), "123,456,789.125 KWD"),
        NetWorthPointRow(LocalDate.of(2026, 8, 2), BigDecimal("-98765432.500"), "-98,765,432.500 KWD"),
    ),
    lowestDisplay = "-98,765,432.500 KWD",
    highestDisplay = "123,456,789.125 KWD",
    direction = TrendDirection.DOWN,
    crossesZero = true,
)

@Composable
private fun SpendingPreview(state: SpendingUiState, dark: Boolean = false) {
    MyWalletTheme(darkTheme = dark, dynamicColor = false) {
        SpendingBreakdownContent(state, DateWindowActions.None, onBack = {}, onRetry = {}, onManageRates = {})
    }
}

@Composable
private fun NetWorthPreview(state: NetWorthUiState, dark: Boolean = false) {
    MyWalletTheme(darkTheme = dark, dynamicColor = false) {
        NetWorthContent(state, DateWindowActions.None, onBack = {}, onRetry = {}, onManageRates = {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun SpendingReadyPreview() = SpendingPreview(SpendingUiState(PREVIEW_WINDOW, SPENDING_READY))

@Preview(showBackground = true, widthDp = 360, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SpendingDarkPreview() = SpendingPreview(SpendingUiState(PREVIEW_WINDOW, SPENDING_READY), dark = true)

@Preview(showBackground = true, widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
private fun SpendingLongTextPreview() = SpendingPreview(SpendingUiState(PREVIEW_WINDOW, SPENDING_LONG))

@Preview(showBackground = true, widthDp = 360, heightDp = 480)
@Composable
private fun SpendingEmptyPreview() =
    SpendingPreview(SpendingUiState(PREVIEW_WINDOW, SpendingData.Ready("USD", "0.00 USD", emptyList())))

@Preview(showBackground = true, widthDp = 360, heightDp = 480)
@Composable
private fun SpendingMissingRatePreview() = SpendingPreview(SpendingUiState(PREVIEW_WINDOW, SpendingData.MissingRate("EUR")))

@Preview(showBackground = true, widthDp = 360, heightDp = 480)
@Composable
private fun SpendingErrorPreview() = SpendingPreview(SpendingUiState(PREVIEW_WINDOW, loadFailed = true))

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun NetWorthTrendPreview() = NetWorthPreview(NetWorthUiState(PREVIEW_WINDOW, NET_WORTH_TREND))

@Preview(showBackground = true, widthDp = 360, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NetWorthDarkPreview() = NetWorthPreview(NetWorthUiState(PREVIEW_WINDOW, NET_WORTH_TREND), dark = true)

@Preview(showBackground = true, widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
private fun NetWorthLongTextPreview() = NetWorthPreview(NetWorthUiState(PREVIEW_WINDOW, NET_WORTH_LONG))

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun NetWorthNoTrendPreview() = NetWorthPreview(
    NetWorthUiState(PREVIEW_WINDOW, NetWorthData.CurrentOnly("USD", "2,430.00 USD", NoTrendReason.TOO_FEW_POINTS)),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 480)
@Composable
private fun NetWorthMissingRatePreview() = NetWorthPreview(NetWorthUiState(PREVIEW_WINDOW, NetWorthData.MissingRate("EUR")))

@Preview(showBackground = true, widthDp = 360, heightDp = 480)
@Composable
private fun NetWorthTooManyPreview() = NetWorthPreview(NetWorthUiState(PREVIEW_WINDOW, NetWorthData.TooManyLegs))

@Preview(showBackground = true, widthDp = 360, heightDp = 480)
@Composable
private fun NetWorthErrorPreview() = NetWorthPreview(NetWorthUiState(PREVIEW_WINDOW, loadFailed = true))
