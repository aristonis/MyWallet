package org.aristonis.mywallet.ui.account

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.error as semanticsError
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.data.format.MoneyParseException
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.message.toUiMessage

enum class AccountInputField { OPENING_BALANCE, CREDIT_LIMIT, DEFAULT_FEE }

@Composable
internal fun accountFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    errorContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
)

@Composable
internal fun Modifier.accountErrorOutline(hasError: Boolean): Modifier =
    if (hasError) border(1.dp, MaterialTheme.colorScheme.error, MaterialTheme.shapes.medium) else this

internal class AccountFieldFailure(val field: AccountInputField, val uiMessage: UiMessage) : Exception()

internal inline fun <T> parseAccountField(field: AccountInputField, parse: () -> T): T = try {
    parse()
} catch (e: MoneyParseException) {
    throw AccountFieldFailure(field, e.toUiMessage())
} catch (e: WalletException) {
    val message = e.toUiMessage()
    if (field == AccountInputField.DEFAULT_FEE &&
        (message == UiMessage.FeeValueNegative || message == UiMessage.FeePercentAboveHundred ||
            message is UiMessage.FeeTooManyDecimals)
    ) throw AccountFieldFailure(field, message)
    throw e
}

/**
 * The credit limit to save from the form text. A type that is not owed when negative has no limit, so a
 * hidden field never persists a value and never blocks saving, whatever text it still holds. Blank means
 * no limit; anything else goes through the positive-amount path in [currencyCode], so 0, a negative
 * amount or a non-number fails with the parser's typed error.
 */
internal fun MoneyParser.parseCreditLimit(input: String, isShown: Boolean, currencyCode: String): Money? =
    if (!isShown || input.isBlank()) null else parseAmount(input, currencyCode)

/** The opening balance field, with the owed-as-negative hint under it for a credit type. */
@Composable
internal fun OpeningBalanceField(
    value: String,
    typeKey: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    error: UiMessage? = null,
) {
    val hintRes = openingBalanceHintRes(typeKey)
    val errorText = error?.text()
    TextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(stringResource(R.string.field_opening_balance)) },
        isError = errorText != null,
        shape = MaterialTheme.shapes.medium,
        colors = accountFieldColors(),
        supportingText = if (errorText != null || hintRes != null) {
            {
                Column {
                    errorText?.let { Text(it) }
                    hintRes?.let { Text(stringResource(it)) }
                }
            }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().accountErrorOutline(errorText != null)
            .semantics { errorText?.let { semanticsError(it) } },
    )
}

/** The optional credit limit field; the caller shows it only for a type that is owed when negative. */
@Composable
internal fun CreditLimitField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    error: UiMessage? = null,
) {
    val errorText = error?.text()
    TextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(stringResource(R.string.field_credit_limit)) },
        isError = errorText != null,
        shape = MaterialTheme.shapes.medium,
        colors = accountFieldColors(),
        supportingText = errorText?.let { message -> { Text(message) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().accountErrorOutline(errorText != null)
            .semantics { errorText?.let { semanticsError(it) } },
    )
}
