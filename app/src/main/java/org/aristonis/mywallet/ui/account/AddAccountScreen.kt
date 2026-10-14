package org.aristonis.mywallet.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error as semanticsError
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme

/**
 * Add-account screen. [onDone] returns to the caller (Home) — fired both on Cancel/back and once the
 * account is created (the VM's `created` flag). No nav library: the parent toggles this screen.
 */
@Composable
fun AddAccountScreen(
    onDone: () -> Unit,
    viewModel: AddAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.created) {
        if (state.created) {
            onDone()
            viewModel.acknowledgeCreated() // reset the retained flag so a re-open doesn't bounce back
        }
    }
    BackHandler(onBack = onDone)
    AddAccountContent(
        state = state,
        onCurrencySelected = viewModel::selectCurrency,
        onNameChanged = viewModel::setName,
        onTypeSelected = viewModel::selectAccountType,
        onOpeningBalanceChanged = viewModel::setOpeningBalance,
        onCreditLimitChanged = viewModel::setCreditLimit,
        onFeeModeChanged = viewModel::selectFeeMode,
        onFeeValueChanged = viewModel::setFeeValue,
        onSubmit = viewModel::submit,
        onCancel = onDone,
    )
}

@Composable
internal fun AddAccountContent(
    state: AddAccountUiState,
    onCurrencySelected: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onTypeSelected: (String) -> Unit,
    onOpeningBalanceChanged: (String) -> Unit,
    onCreditLimitChanged: (String) -> Unit,
    onFeeModeChanged: (FeeMode?) -> Unit,
    onFeeValueChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val dimensions = WalletTheme.dimensions
    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.add_account_title), onBack = onCancel)
        },
    ) { innerPadding ->
        val selectedCurrency = state.currencies.firstOrNull { it.code == state.selectedCurrencyCode }
        val selectedType = AccountTypeRegistry.BuiltIns.all.firstOrNull { it.key == state.accountTypeKey }
        val nameError = state.error.takeIf { it == UiMessage.NameRequired || it == UiMessage.NameBlank }
        val nameErrorText = nameError?.text()

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(dimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.regionGap),
        ) {
            TextField(
                value = state.name,
                onValueChange = onNameChanged,
                enabled = !state.isSubmitting,
                label = { Text(stringResource(R.string.field_account_name)) },
                isError = nameError != null,
                shape = MaterialTheme.shapes.medium,
                colors = accountFieldColors(),
                supportingText = nameErrorText?.let { message -> { Text(message) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().accountErrorOutline(nameErrorText != null)
                    .semantics { nameErrorText?.let { semanticsError(it) } },
            )

            AccountDropdownField(
                label = stringResource(R.string.field_account_type),
                selectedText = selectedType?.let { accountTypeLabel(it.key) }
                    ?: stringResource(R.string.value_select_prompt),
                items = AccountTypeRegistry.BuiltIns.all,
                itemLabel = { accountTypeLabel(it.key) },
                onSelect = { onTypeSelected(it.key) },
                enabled = !state.isSubmitting,
            )

            AccountCurrencyField(
                label = stringResource(R.string.field_currency),
                selected = selectedCurrency,
                currencies = state.currencies,
                onSelect = onCurrencySelected,
                enabled = !state.isSubmitting,
            )

            OpeningBalanceField(
                value = state.openingBalanceInput,
                typeKey = state.accountTypeKey,
                onValueChange = onOpeningBalanceChanged,
                enabled = !state.isSubmitting,
                error = state.error.takeIf { state.errorField == AccountInputField.OPENING_BALANCE },
            )

            if (state.showsCreditLimit) {
                CreditLimitField(
                    value = state.creditLimitInput,
                    onValueChange = onCreditLimitChanged,
                    enabled = !state.isSubmitting,
                    error = state.error.takeIf { state.errorField == AccountInputField.CREDIT_LIMIT },
                )
            }

            AccountFeeEditor(
                mode = state.feeMode,
                value = state.feeValueInput,
                currencyCode = state.selectedCurrencyCode,
                onModeChanged = onFeeModeChanged,
                onValueChanged = onFeeValueChanged,
                enabled = !state.isSubmitting,
                error = state.error.takeIf { state.errorField == AccountInputField.DEFAULT_FEE },
            )

            state.error?.takeIf { state.errorField == null && it != nameError }?.let { message ->
                Text(message.text(), color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = dimensions.minTouchTarget),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(dimensions.iconSize), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.action_save_account))
                }
            }
        }
    }
}

private class AddAccountPreviewStates : PreviewParameterProvider<AddAccountUiState> {
    private val populated = AddAccountUiState(
        currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
        selectedCurrencyCode = "EUR",
        name = "Euro Savings",
        openingBalanceInput = "250",
    )
    override val values = sequenceOf(
        populated,
        populated.copy(openingBalanceInput = "bad amount", error = UiMessage.NotANumber,
            errorField = AccountInputField.OPENING_BALANCE),
    )
}

@Preview(showBackground = true, widthDp = 360)
@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AddAccountPreview(@PreviewParameter(AddAccountPreviewStates::class) state: AddAccountUiState) {
    MyWalletTheme(dynamicColor = false) {
        AddAccountContent(
            state = state,
            onCurrencySelected = {},
            onNameChanged = {},
            onTypeSelected = {},
            onOpeningBalanceChanged = {},
            onCreditLimitChanged = {},
            onFeeModeChanged = {},
            onFeeValueChanged = {},
            onSubmit = {},
            onCancel = {},
        )
    }
}
