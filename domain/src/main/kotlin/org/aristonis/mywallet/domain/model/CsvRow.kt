package org.aristonis.mywallet.domain.model

import java.math.BigDecimal
import java.time.LocalDate

/** One category leg exported with exact amounts and the labels belonging to the export snapshot. */
data class CsvRow(
    val date: LocalDate,
    val entryId: Long,
    val kind: String,
    val line: String,
    val category: String,
    val subCategory: String,
    val side: CategoryKind,
    val amount: BigDecimal,
    val currency: String,
    val rateToBase: BigDecimal,
    val amountInBase: BigDecimal,
    val account: String,
    val counterparty: String,
    val note: String,
)
