package org.aristonis.mywallet.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.TextButton
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
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.components.AristonisLoader
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme

/**
 * Edit-account screen. [onDone] returns to the caller (Manage Accounts) — fired on Cancel/back and once
 * the edit is saved (the VM's `saved` flag). The currency picker is disabled once the account has
 * transactions, since the domain refuses a currency change there. No nav library: the parent toggles this.
 */
@Composable
fun EditAccountScreen(
    accountId: Long,
    onDone: () -> Unit,
    viewModel: EditAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Re-hydrate whenever the target id changes; the view model is retained across the Home toggle.
    LaunchedEffect(accountId) { viewModel.load(accountId) }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            onDone()
            viewModel.acknowledgeSaved() // reset the retained flag so a re-open doesn't bounce back
        }
    }
    BackHandler(onBack = onDone)
    EditAccountContent(
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
        onRetry = viewModel::retry,
    )
}

@Composable
private fun EditAccountContent(
    state: EditAccountUiState,
    onCurrencySelected: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onTypeSelected: (String) -> Unit,
    onOpeningBalanceChanged: (String) -> Unit,
    onCreditLimitChanged: (String) -> Unit,
    onFeeModeChanged: (FeeMode?) -> Unit,
    onFeeValueChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val dimensions = WalletTheme.dimensions
    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.edit_account_title), onBack = onCancel)
        },
    ) { innerPadding ->
        val error = state.error
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                // A missing row is a fail-loud dead end, not a blank editor — show why and offer Back.
                // A failed read is not a missing account: the row may still be there, so offer to read it again.
                state.loaded == null && state.loadFailed -> LoadFailedState(
                    message = (state.loadFailure ?: UiMessage.DataUnreadable).text(),
                    onRetry = onRetry,
                    modifier = Modifier.padding(dimensions.screenPadding),
                )
                state.loaded == null && error != null -> MissingAccount(message = error.text(), onCancel = onCancel)
                state.loaded == null -> AristonisLoader(Modifier.fillMaxSize())
                else -> EditAccountForm(
                    state = state,
                    onCurrencySelected = onCurrencySelected,
                    onNameChanged = onNameChanged,
                    onTypeSelected = onTypeSelected,
                    onOpeningBalanceChanged = onOpeningBalanceChanged,
                    onCreditLimitChanged = onCreditLimitChanged,
                    onFeeModeChanged = onFeeModeChanged,
                    onFeeValueChanged = onFeeValueChanged,
                    onSubmit = onSubmit,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun EditAccountForm(
    state: EditAccountUiState,
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
    val selectedCurrency = state.currencies.firstOrNull { it.code == state.selectedCurrencyCode }
    val selectedType = AccountTypeRegistry.BuiltIns.all.firstOrNull { it.key == state.accountTypeKey }
    val nameError = state.error.takeIf { it == UiMessage.NameRequired || it == UiMessage.NameBlank }
    val nameErrorText = nameError?.text()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(dimensions.regionGap),
    ) {
        TextField(
            value = state.name,
            onValueChange = onNameChanged,
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
        )

        if (state.currencyLocked) {
            LockedCurrencyField(
                currencyCode = state.selectedCurrencyCode ?: stringResource(R.string.value_missing),
            )
        } else {
            AccountCurrencyField(
                label = stringResource(R.string.field_currency),
                selected = selectedCurrency,
                currencies = state.currencies,
                onSelect = onCurrencySelected,
            )
        }

        OpeningBalanceField(
            value = state.openingBalanceInput,
            typeKey = state.accountTypeKey,
            onValueChange = onOpeningBalanceChanged,
            error = state.error.takeIf { state.errorField == AccountInputField.OPENING_BALANCE },
        )

        if (state.showsCreditLimit) {
            CreditLimitField(
                value = state.creditLimitInput,
                onValueChange = onCreditLimitChanged,
                error = state.error.takeIf { state.errorField == AccountInputField.CREDIT_LIMIT },
            )
        }

        AccountFeeEditor(
            mode = state.feeMode,
            value = state.feeValueInput,
            currencyCode = state.selectedCurrencyCode,
            onModeChanged = onFeeModeChanged,
            onValueChanged = onFeeValueChanged,
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

/** A disabled currency field with a short hint, shown once the account has transactions (currency fixed). */
@Composable
private fun LockedCurrencyField(currencyCode: String) {
    Column(verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap)) {
        AccountChoiceSurface(
            label = stringResource(R.string.field_currency),
            value = currencyCode,
            enabled = false,
            onClick = {},
        )
        Text(
            stringResource(R.string.account_currency_locked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MissingAccount(message: String, onCancel: () -> Unit) {
    val dimensions = WalletTheme.dimensions
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(dimensions.regionGap),
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_back)) }
    }
}

private class EditAccountPreviewStates : PreviewParameterProvider<EditAccountUiState> {
    private val loaded = Account(
        id = 1,
        name = "Euro Savings",
        typeKey = "savings",
        currencyCode = "EUR",
        openingBalance = Money.of("250", "EUR"),
    )
    private val normal = EditAccountUiState(
        loaded = loaded,
        currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
        selectedCurrencyCode = "EUR",
        name = "Euro Savings",
        accountTypeKey = "savings",
        openingBalanceInput = "250",
        currencyLocked = true,
    )
    override val values = sequenceOf(
        normal,
        normal.copy(openingBalanceInput = "bad amount", error = UiMessage.NotANumber,
            errorField = AccountInputField.OPENING_BALANCE),
    )
}

@Preview(showBackground = true, widthDp = 360)
@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EditAccountPreview(@PreviewParameter(EditAccountPreviewStates::class) state: EditAccountUiState) {
    MyWalletTheme(dynamicColor = false) {
        EditAccountContent(
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
            onRetry = {},
        )
    }
}
