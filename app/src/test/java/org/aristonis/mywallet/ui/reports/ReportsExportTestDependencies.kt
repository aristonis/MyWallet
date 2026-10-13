package org.aristonis.mywallet.ui.reports

import android.net.Uri
import org.aristonis.mywallet.data.csv.RfcCsvEncoder
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.port.CsvExportSnapshot
import org.aristonis.mywallet.domain.port.CsvExportSource
import org.aristonis.mywallet.domain.usecase.ExportTrackingCsv
import org.aristonis.mywallet.ui.settings.DocumentIo
import java.io.InputStream
import java.io.OutputStream

/** Existing report-read tests must fail if they accidentally start an export. */
internal fun unusedTrackingCsvExport() = ExportTrackingCsv(object : CsvExportSource {
    override suspend fun read(range: DateRange): CsvExportSnapshot = error("report reads must not export")
}, RfcCsvEncoder())

internal object UnusedReportsDocumentIo : DocumentIo {
    override suspend fun write(uri: Uri, block: suspend (OutputStream) -> Unit) = error("report reads must not write")
    override suspend fun read(uri: Uri, block: suspend (InputStream) -> Unit) = error("report reads must not import")
}
