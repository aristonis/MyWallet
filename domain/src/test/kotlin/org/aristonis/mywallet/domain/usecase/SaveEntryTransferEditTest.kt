package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Editing a transfer keeps the rules it had before entries were stored as legs: the destination is
 * re-derived from the source at the transfer's own stored rate, and the currency pair cannot change
 * because that rate would no longer mean anything.
 */
class SaveEntryTransferEditTest {

    private val eurAccount = EntryWorld.account(4, "EUR")
    private val accounts = listOf(EntryWorld.usdAccount, EntryWorld.sypAccount, EntryWorld.secondUsdAccount, eurAccount)

    private fun usdToEur(source: String = "10", dest: String = "9.00", rate: String = "0.90") = Transaction.Transfer(
        id = 1, sourceAccountId = 1, destAccountId = 4,
        sourceAmount = Money.of(source, "USD"), destAmount = Money.of(dest, "EUR"),
        rateUsed = BigDecimal(rate), date = EntryWorld.day,
    )

    private fun rigWith(transfer: Transaction.Transfer) =
        SaveEntryRig(initialEntries = listOf(transfer), accounts = accounts, savedRates = mapOf("EUR" to "1.1"))

    private suspend fun SaveEntryRig.storedTransfer() = entries.findById(1) as Transaction.Transfer

    @Test
    fun editingASameCurrencyTransferMovesTheDestinationWithTheSource() = runTest {
        val transfer = Transaction.Transfer(
            id = 1, sourceAccountId = 1, destAccountId = 3,
            sourceAmount = Money.of("40", "USD"), destAmount = Money.of("40", "USD"),
            rateUsed = BigDecimal.ONE, date = EntryWorld.day,
        )
        val rig = SaveEntryRig(initialEntries = listOf(transfer), accounts = accounts)

        rig.saveEntry(transfer.copy(sourceAmount = Money.of("25", "USD")), typedRates = emptyMap())

        assertEquals(Money.of("25", "USD"), rig.storedTransfer().destAmount)
    }

    @Test
    fun editingTheSourceAmountRecomputesTheDestinationAtTheStoredRate() = runTest {
        val rig = rigWith(usdToEur())

        // the form still sends the old destination; the stored rate 0.90 decides, not the saved EUR rate
        rig.saveEntry(usdToEur().copy(sourceAmount = Money.of("20", "USD")), typedRates = emptyMap())

        val saved = rig.storedTransfer()
        assertEquals(Money.of("20", "USD"), saved.sourceAmount)
        assertEquals(Money.of("18.00", "EUR"), saved.destAmount)
        assertEquals(0, BigDecimal("0.90").compareTo(saved.rateUsed))
    }

    @Test
    fun editingOnlyTheNoteMovesNoMoney() = runTest {
        val rig = rigWith(usdToEur())

        rig.saveEntry(usdToEur().copy(note = "lunch"), typedRates = emptyMap())

        val saved = rig.storedTransfer()
        assertEquals(Money.of("10", "USD"), saved.sourceAmount)
        assertEquals(Money.of("9.00", "EUR"), saved.destAmount)
        assertEquals("lunch", saved.note)
    }

    @Test
    fun aSourceThatConvertsToZeroIsRefusedAndTheTransferIsKept() = runTest {
        val stored = usdToEur(dest = "0.01", rate = "0.001")
        val rig = rigWith(stored)

        val failure = runCatching {
            rig.saveEntry(stored.copy(sourceAmount = Money.of("0.01", "USD")), typedRates = emptyMap())
        }.exceptionOrNull()

        assertTrue(failure is WalletException.AmountRoundsToZero)
        assertEquals(stored, rig.storedTransfer())
    }

    @Test
    fun movingTheDestinationToAnotherCurrencyIsRefused() = runTest {
        val rig = rigWith(usdToEur())

        val failure = runCatching {
            rig.saveEntry(usdToEur().copy(destAccountId = 2, destAmount = Money.of("9.00", "SYP")), typedRates = emptyMap())
        }.exceptionOrNull()

        assertTrue(failure is WalletException.TransferCurrencyPairChanged)
        assertEquals(usdToEur(), rig.storedTransfer())
    }

    @Test
    fun movingTheSourceToAnAccountInTheSameCurrencyIsAllowed() = runTest {
        val rig = rigWith(usdToEur())

        rig.saveEntry(usdToEur().copy(sourceAccountId = 3), typedRates = emptyMap())

        val saved = rig.storedTransfer()
        assertEquals(3L, saved.sourceAccountId)
        assertEquals(Money.of("9.00", "EUR"), saved.destAmount)
    }
}
