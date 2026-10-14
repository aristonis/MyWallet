package org.aristonis.mywallet.ui.account

import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.transaction.TransactionRow

/**
 * What the account page shows. [header] is null while loading, when the account does not exist
 * ([notFound]) and when the read failed ([loadFailure]); the three are distinct on screen, and none
 * of them shows a balance of zero.
 */
internal data class AccountDetailUiState(
    val isLoading: Boolean = true,
    val header: AccountDetailHeader? = null,
    val notFound: Boolean = false,
    val loadFailure: UiMessage? = null,
    val entries: List<TransactionRow> = emptyList(),
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
)

/**
 * The account's identity and current position, already formatted. [nativeDisplay] is the balance in
 * the account's own currency; [baseDisplay] is the converted line, null when the account is in the
 * base currency or the rate is missing ([missingRate]).
 */
internal data class AccountDetailHeader(
    val id: Long,
    val name: String,
    val typeKey: String,
    val currencyCode: String,
    val archived: Boolean,
    val nativeDisplay: String,
    val baseDisplay: String? = null,
    val missingRate: Boolean = false,
    val credit: CreditDetail? = null,
)

/** A credit account's position: [availableDisplay] is null when the account has no limit. */
internal data class CreditDetail(
    val owedDisplay: String,
    val limitDisplay: String? = null,
    val availableDisplay: String? = null,
    val overLimit: Boolean = false,
)
