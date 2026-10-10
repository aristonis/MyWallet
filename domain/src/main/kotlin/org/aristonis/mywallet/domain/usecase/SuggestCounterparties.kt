package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.port.DebtRepository

/** Prefix suggestions use the repository's name-key matching, including names on hidden debts. */
class SuggestCounterparties(private val debts: DebtRepository) {
    suspend operator fun invoke(prefix: String): List<String> = debts.counterpartyNames(prefix)
}
