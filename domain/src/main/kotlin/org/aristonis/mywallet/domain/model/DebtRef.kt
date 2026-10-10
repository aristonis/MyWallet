package org.aristonis.mywallet.domain.model

/** A person's running debt, resolved to a row inside the save transaction. */
data class DebtRef(val counterparty: String, val side: DebtSide, val currencyCode: String)
