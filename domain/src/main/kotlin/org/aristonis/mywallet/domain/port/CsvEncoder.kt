package org.aristonis.mywallet.domain.port

import org.aristonis.mywallet.domain.model.CsvRow

/** Encodes category rows as portable spreadsheet-safe CSV text. */
interface CsvEncoder {
    fun encode(rows: List<CsvRow>): String
}
