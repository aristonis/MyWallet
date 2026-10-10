package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Assumed constructor: `GetAccountBalances(accounts: AccountRepository, ledger: LedgerQueries)`.
 * Balances come from account legs, so nothing reads entries.
 */
class GetAccountBalancesTest {

    private val day = LocalDate.of(2026, 7, 2)
    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD"))

    @Test
    fun combinesAccountsAndAccountLegsIntoLiveBalances() = runTest {
        val entries = FakeEntryRepository(
            listOf(Transaction.Income(id = 1, accountId = 1, amount = Money.of("25", "USD"), categoryId = 1, date = day)),
        )

        val balances = GetAccountBalances(FakeAccountRepository(listOf(cash)), FakeLedgerQueries(entries))().first()

        assertEquals(Money.of("125", "USD"), balances.single().balance)
    }

    @Test
    fun aNewEntryChangesTheNextBalanceRead() = runTest {
        val entries = FakeEntryRepository()
        val balances = GetAccountBalances(FakeAccountRepository(listOf(cash)), FakeLedgerQueries(entries))
        assertEquals(Money.of("100", "USD"), balances().first().single().balance)

        entries.insert(Transaction.Expense(accountId = 1, amount = Money.of("40", "USD"), categoryId = 2, date = day))

        assertEquals(Money.of("60", "USD"), balances().first().single().balance)
    }

    @Test
    fun anAccountWithNoLegsKeepsItsOpeningBalance() = runTest {
        val balances = GetAccountBalances(FakeAccountRepository(listOf(cash)), FakeLedgerQueries(FakeEntryRepository()))().first()

        assertEquals(Money.of("100", "USD"), balances.single().balance)
    }

    private fun usd(a: String) = Money.of(a, "USD")

    private fun account(id: Long, typeKey: String, opening: String = "0", limit: Money? = null) = Account(
        id = id, name = "Account $id", typeKey = typeKey, currencyCode = "USD",
        openingBalance = usd(opening), creditLimit = limit,
    )

    private fun purchaseOnCard(cardId: Long, amount: String) =
        Transaction.Expense(accountId = cardId, amount = usd(amount), categoryId = 2, date = day)

    private fun payCard(fromId: Long, cardId: Long, amount: String) = Transaction.Transfer(
        sourceAccountId = fromId, destAccountId = cardId, sourceAmount = usd(amount),
        destAmount = usd(amount), rateUsed = java.math.BigDecimal.ONE, date = day,
    )

    @Test
    fun creditCardPurchaseShowsWhatIsOwedAndWhatIsAvailable() = runTest {
        val credit = account(1, "credit_card", limit = usd("2000"))
        val entries = FakeEntryRepository(listOf(purchaseOnCard(1, "450")))

        val row = GetAccountBalances(FakeAccountRepository(listOf(credit)), FakeLedgerQueries(entries))().first().single()

        assertEquals(usd("-450"), row.balance)
        assertEquals(usd("450"), row.credit?.owed)
        assertEquals(usd("1550"), row.credit?.available)
        assertEquals(false, row.credit?.overLimit)
    }

    @Test
    fun payingTheCardFromAnotherAccountClearsWhatIsOwed() = runTest {
        val credit = account(1, "credit_card", limit = usd("2000"))
        val bank = account(2, "bank", opening = "1000")
        val entries = FakeEntryRepository(listOf(purchaseOnCard(1, "450")))
        val balances = GetAccountBalances(FakeAccountRepository(listOf(credit, bank)), FakeLedgerQueries(entries))
        assertEquals(usd("450"), balances().first().first { it.account.id == 1L }.credit?.owed)

        entries.insert(payCard(fromId = 2, cardId = 1, amount = "450"))

        val after = balances().first()
        val card = after.first { it.account.id == 1L }
        assertEquals(usd("0"), card.balance)
        assertEquals(usd("0"), card.credit?.owed)
        assertEquals(usd("2000"), card.credit?.available)
        assertEquals(usd("550"), after.first { it.account.id == 2L }.balance)
        assertNull(after.first { it.account.id == 2L }.credit)
    }

    @Test
    fun spendingPastTheLimitIsRecordedAndFlagged() = runTest {
        val credit = account(1, "credit_card", limit = usd("2000"))
        val entries = FakeEntryRepository(listOf(purchaseOnCard(1, "2100")))

        val row = GetAccountBalances(FakeAccountRepository(listOf(credit)), FakeLedgerQueries(entries))().first().single()

        assertEquals(usd("-2100"), row.balance)
        assertEquals(true, row.credit?.overLimit)
    }

    @Test
    fun anArchivedCreditCardThatStillOwesKeepsItsCreditStatus() = runTest {
        val credit = account(1, "credit_card", limit = usd("2000")).copy(archived = true)
        val entries = FakeEntryRepository(listOf(purchaseOnCard(1, "300")))

        val row = GetAccountBalances(FakeAccountRepository(listOf(credit)), FakeLedgerQueries(entries))().first().single()

        assertEquals(usd("300"), row.credit?.owed)
    }

    @Test
    fun creditCardWithoutALimitShowsOwedOnly() = runTest {
        val credit = account(1, "credit_card")
        val entries = FakeEntryRepository(listOf(purchaseOnCard(1, "450")))

        val credit1 = GetAccountBalances(FakeAccountRepository(listOf(credit)), FakeLedgerQueries(entries))().first().single().credit

        assertEquals(usd("450"), credit1?.owed)
        assertNull(credit1?.available)
        assertEquals(false, credit1?.overLimit)
    }

    @Test
    fun aDebitCardStaysAnAssetAndItsNegativeBalanceReadsUnchanged() = runTest {
        val visa = account(1, "card", opening = "-1146.04")

        val row = GetAccountBalances(FakeAccountRepository(listOf(visa)), FakeLedgerQueries(FakeEntryRepository()))().first().single()

        assertEquals(usd("-1146.04"), row.balance)
        assertNull(row.credit)
    }

    @Test
    fun anUnknownTypeKeyHasNoCreditStatus() = runTest {
        val odd = account(1, "mystery", opening = "-50")

        val row = GetAccountBalances(FakeAccountRepository(listOf(odd)), FakeLedgerQueries(FakeEntryRepository()))().first().single()

        assertEquals(usd("-50"), row.balance)
        assertNull(row.credit)
    }

    @Test
    fun aCustomOwedWhenNegativeTypeInTheGivenRegistryGetsCreditStatus() = runTest {
        val registry = org.aristonis.mywallet.domain.model.AccountTypeRegistry().apply {
            register(org.aristonis.mywallet.domain.model.AccountType("store_card", owedWhenNegative = true))
        }
        val store = account(1, "store_card", opening = "-30")

        val row = GetAccountBalances(
            FakeAccountRepository(listOf(store)), FakeLedgerQueries(FakeEntryRepository()), registry,
        )().first().single()

        assertEquals(usd("30"), row.credit?.owed)
    }

    @Test
    fun otherAssetTypesNeverGetCreditStatus() = runTest {
        val rows = GetAccountBalances(
            FakeAccountRepository(listOf(cash, account(2, "savings"), account(3, "bank"), account(4, "other"))),
            FakeLedgerQueries(FakeEntryRepository()),
        )().first()

        assertEquals(listOf(null, null, null, null), rows.map { it.credit })
    }
}
