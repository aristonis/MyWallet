package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.port.BackupRepository

/**
 * Replaces the whole wallet with a previously exported snapshot. Any rejection — corrupt data or a
 * format version this build can't read — propagates as a typed error so the caller can react; it is
 * never swallowed, and a failed restore leaves the existing data untouched.
 */
class RestoreBackup(private val backups: BackupRepository) {
    suspend operator fun invoke(serialized: String) = backups.restoreBackup(serialized)
}
