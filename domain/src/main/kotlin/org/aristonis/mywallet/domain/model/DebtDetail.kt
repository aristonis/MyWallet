package org.aristonis.mywallet.domain.model

/** One debt and its complete entry history, newest date first and then newest id first. */
data class DebtDetail(val summary: DebtSummary, val entries: List<Transaction>)
