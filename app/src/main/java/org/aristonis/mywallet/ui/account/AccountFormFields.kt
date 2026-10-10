package org.aristonis.mywallet.ui.account

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import org.aristonis.mywallet.R
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Money

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
internal fun OpeningBalanceField(value: String, typeKey: String, onValueChange: (String) -> Unit) {
    val hintRes = openingBalanceHintRes(typeKey)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.field_opening_balance)) },
        supportingText = hintRes?.let { res -> { Text(stringResource(res)) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The optional credit limit field; the caller shows it only for a type that is owed when negative. */
@Composable
internal fun CreditLimitField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.field_credit_limit)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
