package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.port.BackupRepository

/**
 * Produces a portable snapshot of the whole wallet as an opaque string — the seam a future backup
 * screen binds to. The concrete on-disk format lives in `:data`; the use-case just asks for it.
 */
class ExportBackup(private val backups: BackupRepository) {
    suspend operator fun invoke(): String = backups.exportBackup()
}
