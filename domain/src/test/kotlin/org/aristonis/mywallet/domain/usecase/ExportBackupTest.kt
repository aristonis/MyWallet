package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.usecase.fake.FakeBackupRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportBackupTest {

    @Test
    fun returnsWhateverTheRepositorySerialized() = runTest {
        val serialized = """{"version":1}"""
        val backups = FakeBackupRepository(exported = serialized)

        val result = ExportBackup(backups).invoke()

        assertEquals(serialized, result)
    }
}
