package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.port.BackupRepository
import org.aristonis.mywallet.domain.port.PreparedBackup

/**
 * Takes a checked wallet snapshot, ready to write. Checking comes first so the caller opens the
 * chosen document only for a backup that restore will accept.
 */
class ExportBackup(private val backups: BackupRepository) {
    suspend operator fun invoke(): PreparedBackup = backups.prepareBackup()
}
