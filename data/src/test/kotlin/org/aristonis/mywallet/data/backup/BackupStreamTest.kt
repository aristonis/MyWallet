package org.aristonis.mywallet.data.backup

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream

/** Streaming keeps both file formats readable without building a serialized wallet in memory. */
class BackupStreamTest {
    @get:Rule val folder = TemporaryFolder()
    private val sample = BackupV2Samples.wallet()

    @Test fun formatThreeExportRestoresEveryStoredValueThroughStreamsAndRemovesScratch() {
        val backup = BackupV2Samples.versionThreeWallet()
        val output = ByteArrayOutputStream()
        BackupCodec.encode(BackupCodec.validate(backup), output)

        val restored = output.toByteArray().inputStream().use { decodeValidated(it, folder.root) }

        assertEquals(3, restored.version)
        assertEquals(backup, restored)
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test fun formatThreeWithABrokenLegIsInvalidAndRemovesScratch() {
        val backup = BackupV2Samples.versionThreeWallet().let { original ->
            original.copy(legs = original.legs.filterNot {
                it.entryId == BackupV2Samples.TRANSFER_ENTRY && it.role == "DEST"
            })
        }
        val text = BackupCodec.encode(backup)

        assertThrows(WalletException.BackupInvalid::class.java) {
            text.byteInputStream().use { decodeValidated(it, folder.root) }
        }

        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test fun compactStreamRoundTripPreservesEveryValue() {
        val output = ByteArrayOutputStream()
        BackupCodec.encode(BackupCodec.validate(sample), output)
        assertFalse(output.toString("UTF-8").contains('\n'))
        assertEquals(sample, decodeValidated(output.toByteArray().inputStream(), folder.root))
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test fun versionLastStillSelectsTheOldFormatConverter() {
        val old = BackupV2Samples.versionOneText().replace("\"version\":1,", "").dropLast(1) + ",\"version\":1}"
        val restored = decodeValidated(old.byteInputStream(), folder.root)
        assertEquals(2, restored.version)
        assertEquals(listOf("INCOME", "EXPENSE", "TRANSFER"), restored.entries.map { it.kind })
    }

    @Test fun newerVersionWithUnknownFieldsIsRefusedAndScratchRemoved() {
        assertThrows(WalletException.BackupVersionUnsupported::class.java) {
            decodeValidated("{\"unknown\":[],\"version\":9}".byteInputStream(), folder.root)
        }
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test fun malformedInputIsRefusedAndScratchRemoved() {
        assertThrows(WalletException.BackupInvalid::class.java) {
            decodeValidated("{ not json".byteInputStream(), folder.root)
        }
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test fun failedInputCopyRemovesScratchAndKeepsCallerStreamOpen() {
        var closed = false
        val input = object : FilterInputStream("{}".byteInputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = throw java.io.IOException("failed")
            override fun close() { closed = true }
        }
        assertThrows(java.io.IOException::class.java) { decodeValidated(input, folder.root) }
        assertFalse(closed)
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test fun cancellationDuringInputCopyIsPreservedAndRemovesScratch() {
        val cancelled = kotlinx.coroutines.CancellationException("cancelled")
        val input = object : FilterInputStream("{}".byteInputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = throw cancelled
        }
        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            decodeValidated(input, folder.root)
        }
        assertEquals(cancelled, thrown)
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test fun callerOwnedOutputStaysOpenAfterEncoding() {
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { closed = true }
        }
        BackupCodec.encode(BackupCodec.validate(sample), output)
        assertFalse(closed)
        assertEquals(sample, decodeValidated(output.toByteArray().inputStream(), folder.root))
    }

    @Test fun olderBackupWithNoVersionUsesTheFrozenConverter() {
        val old = BackupV2Samples.versionOneText().replace("\"version\":1,", "")
        assertEquals(2, decodeValidated(old.byteInputStream(), folder.root).version)
    }

    @Test fun oldUnknownRowAndOrphanRawCopyStayRestorable() {
        val old = BackupV2Samples.versionOneText(BackupV2Samples.versionOneTransactions() +
            BackupV2Samples.versionOneTransaction(20, "ODD", "2026-01-01", 1, "9.00", "USD"))
        val converted = decodeValidated(old.byteInputStream(), folder.root)
        assertEquals("LEGACY_UNREADABLE", converted.entries.single { it.id == 20L }.kind)
        val orphan = converted.copy(entries = converted.entries.filterNot { it.id == 20L })
        val output = ByteArrayOutputStream()
        BackupCodec.encode(BackupCodec.validate(orphan), output)
        assertEquals(orphan, decodeValidated(output.toByteArray().inputStream(), folder.root))
    }

    @Test fun leftoverScratchFromAnEarlierRestoreIsRemovedAndOtherFilesKept() {
        // A restore killed mid-copy never reaches its own cleanup, so the next one sweeps for it.
        val leftover = java.io.File(folder.root, "wallet-restore-123.json").apply { writeText("{\"version\":2}") }
        val unrelated = java.io.File(folder.root, "thumbnail.png").apply { writeText("x") }
        decodeValidated(BackupCodec.encode(sample).byteInputStream(), folder.root)
        assertFalse(leftover.exists())
        assertEquals(listOf("thumbnail.png"), folder.root.list()!!.toList())
        assertEquals("x", unrelated.readText())
    }

    @Test fun leftoverScratchIsRemovedEvenWhenTheNewFileIsRefused() {
        val leftover = java.io.File(folder.root, "wallet-restore-9.json").apply { writeText("{}") }
        assertThrows(WalletException.BackupInvalid::class.java) {
            decodeValidated("{ not json".byteInputStream(), folder.root)
        }
        assertFalse(leftover.exists())
    }

    @Test fun invalidExportIsRefusedBeforeAnyOutputExists() {
        val bad = sample.copy(accounts = sample.accounts.map {
            if (it.id == 1L) it.copy(defaultFeeMode = "PERCENT", defaultFeeValue = "101") else it
        })
        // Validation needs no stream, so a refused wallet never reaches the point of opening one.
        assertThrows(WalletException.BackupInvalid::class.java) { BackupCodec.validate(bad) }
    }
}
