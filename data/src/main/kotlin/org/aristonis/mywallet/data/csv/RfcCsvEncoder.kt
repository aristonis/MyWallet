package org.aristonis.mywallet.data.csv

import org.aristonis.mywallet.domain.model.CsvRow
import org.aristonis.mywallet.domain.port.CsvEncoder

/** RFC 4180 CSV with a UTF-8 BOM and formula neutralization on textual cells only. */
class RfcCsvEncoder : CsvEncoder {
    override fun encode(rows: List<CsvRow>): String = buildString {
        append('\uFEFF')
        append(HEADER)
        append("\r\n")
        rows.forEach { row ->
            val cells = listOf(
                text(row.date.toString()), row.entryId.toString(), text(row.kind), text(row.line),
                text(row.category), text(row.subCategory), text(row.side.name), row.amount.toPlainString(),
                text(row.currency), row.rateToBase.toPlainString(), row.amountInBase.toPlainString(),
                text(row.account), text(row.counterparty), text(row.note),
            )
            append(cells.joinToString(","))
            append("\r\n")
        }
    }

    private fun text(value: String): String {
        val safe = if (value.firstOrNull() in DANGEROUS_PREFIXES) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"${safe.replace("\"", "\"\"")}\""
        } else safe
    }

    private companion object {
        val DANGEROUS_PREFIXES = setOf('=', '+', '-', '@', '\t', '\r')
        const val HEADER = "date,entry_id,kind,line,category,sub_category,side,amount,currency,rate_to_base," +
            "amount_in_base,account,counterparty,note"
    }
}
