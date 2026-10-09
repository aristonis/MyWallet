package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Balances are read from the account legs, so an edit, a delete and an undo show up in the next
 * balance read with no recalculation step. A transfer moves both of its accounts together.
 */
class EntryWritesMoveBalancesTest {

    private val cash = EntryWorld.usdAccount.copy(openingBalance = Money.of("100", "USD"))
    private val savings = EntryWorld.secondUsdAccount
    private val accounts = listOf(cash, EntryWorld.sypAccount, savings)

    private fun usd(amount: String) = Money.of(amount, "USD")

    private fun balancesOf(rig: SaveEntryRig) = GetAccountBalances(FakeAccountRepository(accounts), rig.ledger)

    private suspend fun GetAccountBalances.of(account: Account): Money =
        invoke().first().single { it.account.id == account.id }.balance

    @Test
    fun editingAnExpenseAmountMovesTheBalance() = runTest {
        val rig = SaveEntryRig(initialEntries = listOf(EntryWorld.expense(id = 4, amount = "40", rate = "1")), accounts = accounts)
        val balances = balancesOf(rig)
        assertEquals(usd("60"), balances.of(cash))

        rig.saveEntry(EntryWorld.expense(id = 4, amount = "25", rate = "1"), typedRates = emptyMap())

        assertEquals(usd("75"), balances.of(cash))
    }

    @Test
    fun editingAnIncomeAmountMovesTheBalance() = runTest {
        val rig = SaveEntryRig(initialEntries = listOf(EntryWorld.income(id = 4, amount = "100", rate = "1")), accounts = accounts)
        val balances = balancesOf(rig)
        assertEquals(usd("200"), balances.of(cash))

        rig.saveEntry(EntryWorld.income(id = 4, amount = "30", rate = "1"), typedRates = emptyMap())

        assertEquals(usd("130"), balances.of(cash))
    }

    @Test
    fun deletingAnIncomeReturnsTheBalanceToItsOpening() = runTest {
        val rig = SaveEntryRig(initialEntries = listOf(EntryWorld.income(id = 4, amount = "100", rate = "1")), accounts = accounts)
        val balances = balancesOf(rig)
        assertEquals(usd("200"), balances.of(cash))

        rig.deleteEntry(4)

        assertEquals(usd("100"), balances.of(cash))
    }

    @Test
    fun deletingATransferClearsBothSides() = runTest {
        val transfer = EntryWorld.transfer(id = 9, sourceAccountId = cash.id, destAccountId = savings.id, amount = "40")
        val rig = SaveEntryRig(initialEntries = listOf(transfer), accounts = accounts)
        val balances = balancesOf(rig)
        assertEquals(usd("60"), balances.of(cash))
        assertEquals(usd("40"), balances.of(savings))

        rig.deleteEntry(9)

        assertEquals(usd("100"), balances.of(cash))
        assertEquals(usd("0"), balances.of(savings))
    }

    @Test
    fun undoingADeletedIncomeBringsItsBalanceBack() = runTest {
        val rig = SaveEntryRig(
            initialEntries = listOf(EntryWorld.income(id = 4, amount = "100", rate = "1")),
            accounts = accounts,
            atomic = true,
        )
        val balances = balancesOf(rig)

        val bundle = rig.deleteEntry(4)
        assertEquals(usd("100"), balances.of(cash))
        rig.restoreEntries(bundle)

        assertEquals(usd("200"), balances.of(cash))
    }

    @Test
    fun undoingADeletedTransferBringsBothSidesBack() = runTest {
        val transfer = EntryWorld.transfer(id = 9, sourceAccountId = cash.id, destAccountId = savings.id, amount = "40")
        val rig = SaveEntryRig(initialEntries = listOf(transfer), accounts = accounts, atomic = true)
        val balances = balancesOf(rig)

        val bundle = rig.deleteEntry(9)
        assertEquals(usd("0"), balances.of(savings))
        rig.restoreEntries(bundle)

        assertEquals(usd("60"), balances.of(cash))
        assertEquals(usd("40"), balances.of(savings))
    }
}
