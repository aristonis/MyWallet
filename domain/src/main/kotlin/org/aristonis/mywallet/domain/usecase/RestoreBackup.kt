package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.port.BackupRepository
import java.io.InputStream

/** Restores a validated snapshot atomically; typed rejections propagate without changing the wallet. */
class RestoreBackup(private val backups: BackupRepository) {
    suspend operator fun invoke(input: InputStream) = backups.restoreBackup(input)
}
