package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.usecase.fake.FakeBackupRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreBackupTest {

    @Test
    fun forwardsTheSerializedStringToTheRepository() = runTest {
        val backups = FakeBackupRepository()

        RestoreBackup(backups).invoke("payload")

        assertEquals("payload", backups.restoredWith)
    }

    // A corrupt backup must surface as a typed failure, never be swallowed into a silent no-op.
    @Test(expected = WalletException.BackupInvalid::class)
    fun propagatesBackupInvalid() = runTest {
        val backups = FakeBackupRepository(restoreFailure = WalletException.BackupInvalid())
        RestoreBackup(backups).invoke("whatever")
    }

    // Restoring a file written by a newer format must fail loud, not partially apply.
    @Test(expected = WalletException.BackupVersionUnsupported::class)
    fun propagatesBackupVersionUnsupported() = runTest {
        val backups = FakeBackupRepository(restoreFailure = WalletException.BackupVersionUnsupported(2))
        RestoreBackup(backups).invoke("whatever")
    }
}
