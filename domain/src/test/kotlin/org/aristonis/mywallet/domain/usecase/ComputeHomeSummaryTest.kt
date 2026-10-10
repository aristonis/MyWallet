package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountBalance
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.HomeSummary
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeFxRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pocket is what the non-archived accounts hold, less a card that is owed; I owe is the I-owe debts
 * plus every owed card (archived too); owed to me is the owed-to-me debts; balance is pocket + owed
 * to me - I owe, at today's rates. A debt's remaining is the sum of its legs. One below zero is left
 * out of every total, needs no rate and is named in `debtsBelowZero`.
 */
class ComputeHomeSummaryTest {

    private val day = LocalDate.of(2026, 7, 10)
    private val currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))

    private fun usd(a: String) = Money.of(a, "USD")
    private fun eur(a: String) = Money.of(a, "EUR")

    private fun cash(id: Long, opening: String, currency: String = "USD", archived: Boolean = false) = Account(
        id = id, name = "Cash $id", typeKey = "cash", currencyCode = currency,
        openingBalance = Money.of(opening, currency), archived = archived,
    )

    private fun card(id: Long, opening: String, currency: String = "USD", archived: Boolean = false) = Account(
        id = id, name = "Card $id", typeKey = "credit_card", currencyCode = currency,
        openingBalance = Money.of(opening, currency), archived = archived,
    )

    /** Debt legs written straight to the store, so a test can hold any sign without a recipe's checks. */
    private fun FakeEntryRepository.debtLeg(entryId: Long, debtId: Long, side: DebtSide, amount: Money) =
        seedRaw(
            EntryParts(
                EntryHeader(entryId, "OWED_BEFORE", day, null),
                listOf(Leg(LegRole.DEBT, 0, LegTarget.Debt(debtId, side), amount)),
            ),
        )

    /** The use case with the balances it is handed, so a test calls it like a flow. */
    private class Home(
        private val use: ComputeHomeSummary,
        private val balances: Flow<List<AccountBalance>>,
    ) {
        operator fun invoke(): Flow<HomeSummary> = use(balances)
    }

    private fun summary(
        accounts: List<Account>,
        entries: FakeEntryRepository = FakeEntryRepository(),
        rates: List<ExchangeRate> = emptyList(),
        fx: FakeFxRepository = fakeFx(currencies, rates, "USD"),
    ): Home {
        val ledger = FakeLedgerQueries(entries)
        val balances = GetAccountBalances(FakeAccountRepository(accounts), ledger)()
        return Home(ComputeHomeSummary(ledger, fx), balances)
    }

    private fun amounts(
        pocket: String,
        owedToMe: String,
        iOwe: String,
        balance: String,
        debtsBelowZero: List<Long> = emptyList(),
    ) = HomeSummary.Amounts(
        pocket = usd(pocket), owedToMe = usd(owedToMe), iOwe = usd(iOwe), balance = usd(balance),
        debtsBelowZero = debtsBelowZero,
    )

    @Test
    fun separatesPocketOwedToMeAndIOweAndNetsThemIntoTheBalance() = runTest {
        val entries = FakeEntryRepository()
        entries.debtLeg(1, 1, DebtSide.OWED_TO_ME, usd("300"))
        entries.debtLeg(2, 2, DebtSide.I_OWE, usd("1450"))

        val result = summary(listOf(cash(1, "700")), entries)().first()

        assertEquals(amounts(pocket = "700", owedToMe = "300", iOwe = "1450", balance = "-450"), result)
    }

    @Test
    fun borrowingAddsToPocketAndToWhatIOweSoTheBalanceDoesNotMove() = runTest {
        val entries = FakeEntryRepository()
        val home = summary(listOf(cash(1, "1000")), entries)
        val before = home().first() as HomeSummary.Amounts

        entries.insert(Transaction.Borrow(accountId = 1, debtId = 1, amount = usd("5000"), date = day))
        val after = home().first()

        assertEquals(amounts(pocket = "6000", owedToMe = "0", iOwe = "5000", balance = "1000"), after)
        assertEquals(before.balance, (after as HomeSummary.Amounts).balance)
    }

    @Test
    fun lendingMovesMoneyFromPocketToOwedToMeAndKeepsTheBalance() = runTest {
        val entries = FakeEntryRepository(
            listOf(Transaction.Lend(accountId = 1, debtId = 1, amount = usd("300"), date = day)),
        )

        val result = summary(listOf(cash(1, "1000")), entries)().first()

        assertEquals(amounts(pocket = "700", owedToMe = "300", iOwe = "0", balance = "1000"), result)
    }

    @Test
    fun aDebtOwedBeforeTheAppGrowsWhatIOweAndLeavesThePocketAlone() = runTest {
        val entries = FakeEntryRepository(
            listOf(Transaction.OwedBefore(debtId = 1, side = DebtSide.I_OWE, amount = usd("400"), date = day)),
        )

        val result = summary(listOf(cash(1, "1000")), entries)().first()

        assertEquals(amounts(pocket = "1000", owedToMe = "0", iOwe = "400", balance = "600"), result)
    }

    @Test
    fun withNoDebtsThePocketIsTheBalance() = runTest {
        val result = summary(listOf(cash(1, "250"), cash(2, "50")))().first()

        assertEquals(amounts(pocket = "300", owedToMe = "0", iOwe = "0", balance = "300"), result)
    }

    @Test
    fun aNegativeBalanceOnAnOrdinaryAccountStaysInThePocketAsANegative() = runTest {
        val entries = FakeEntryRepository(
            listOf(Transaction.Expense(accountId = 1, amount = usd("150"), categoryId = 2, date = day)),
        )

        val result = summary(listOf(cash(1, "100")), entries)().first()

        assertEquals(amounts(pocket = "-50", owedToMe = "0", iOwe = "0", balance = "-50"), result)
    }

    @Test
    fun anArchivedAccountIsOutOfThePocket() = runTest {
        val result = summary(listOf(cash(1, "100"), cash(2, "900", archived = true)))().first()

        assertEquals(amounts(pocket = "100", owedToMe = "0", iOwe = "0", balance = "100"), result)
    }

    @Test
    fun anArchivedAccountInACurrencyWithNoRateDoesNotBlockTheSummary() = runTest {
        val result = summary(listOf(cash(1, "100"), cash(2, "900", currency = "EUR", archived = true)))().first()

        assertEquals(amounts(pocket = "100", owedToMe = "0", iOwe = "0", balance = "100"), result)
    }

    @Test
    fun aCreditCardInCreditCountsInThePocket() = runTest {
        val result = summary(listOf(cash(1, "100"), card(2, "120")))().first()

        assertEquals(amounts(pocket = "220", owedToMe = "0", iOwe = "0", balance = "220"), result)
    }

    @Test
    fun aCreditCardBelowZeroIsInWhatIOweAndNotInThePocket() = runTest {
        val result = summary(listOf(cash(1, "100"), card(2, "-300")))().first()

        assertEquals(amounts(pocket = "100", owedToMe = "0", iOwe = "300", balance = "-200"), result)
    }

    @Test
    fun anArchivedCreditCardThatOwesStillCountsInWhatIOwe() = runTest {
        val result = summary(listOf(cash(1, "100"), card(2, "-300", archived = true)))().first()

        assertEquals(amounts(pocket = "100", owedToMe = "0", iOwe = "300", balance = "-200"), result)
    }

    @Test
    fun anOwingCardAddsToWhatIOweNextToTheDebts() = runTest {
        val entries = FakeEntryRepository()
        entries.debtLeg(1, 1, DebtSide.I_OWE, usd("200"))

        val result = summary(listOf(cash(1, "100"), card(2, "-300")), entries)().first()

        assertEquals(amounts(pocket = "100", owedToMe = "0", iOwe = "500", balance = "-400"), result)
    }

    @Test
    fun aDebtInAnotherCurrencyConvertsAtTodaysRate() = runTest {
        val entries = FakeEntryRepository()
        entries.debtLeg(1, 1, DebtSide.I_OWE, eur("100"))
        entries.debtLeg(2, 2, DebtSide.OWED_TO_ME, eur("50"))

        val result = summary(listOf(cash(1, "0")), entries, listOf(ExchangeRate("EUR", BigDecimal("1.10"))))().first()

        assertEquals(amounts(pocket = "0", owedToMe = "55", iOwe = "110", balance = "-55"), result)
    }

    @Test
    fun aDebtStillOwedInACurrencyWithNoRateReportsTheMissingRate() = runTest {
        val entries = FakeEntryRepository()
        entries.debtLeg(1, 1, DebtSide.I_OWE, eur("80"))

        val result = summary(listOf(cash(1, "100")), entries)().first()

        assertEquals(HomeSummary.MissingRate("EUR"), result)
    }

    @Test
    fun aSettledDebtInACurrencyWithNoRateDoesNotBlockTheSummary() = runTest {
        val entries = FakeEntryRepository()
        entries.debtLeg(1, 1, DebtSide.I_OWE, eur("80"))
        entries.debtLeg(2, 1, DebtSide.I_OWE, eur("-80"))

        val result = summary(listOf(cash(1, "100")), entries)().first()

        assertEquals(amounts(pocket = "100", owedToMe = "0", iOwe = "0", balance = "100"), result)
    }

    @Test
    fun anOwingCardInACurrencyWithNoRateReportsTheMissingRate() = runTest {
        val result = summary(listOf(cash(1, "100"), card(2, "-30", currency = "EUR", archived = true)))().first()

        assertEquals(HomeSummary.MissingRate("EUR"), result)
    }

    @Test
    fun aDebtBelowZeroIsLeftOutOfEveryTotalAndListedAscending() = runTest {
        val entries = FakeEntryRepository()
        entries.debtLeg(1, 1, DebtSide.I_OWE, usd("-40"))
        entries.debtLeg(2, 2, DebtSide.OWED_TO_ME, usd("-25"))

        val result = summary(listOf(cash(1, "100")), entries)().first()

        assertEquals(amounts("100", "0", "0", "100", debtsBelowZero = listOf(1L, 2L)), result)
    }

    @Test
    fun theLegsOfOneDebtAreSummedBeforeTheyCount() = runTest {
        val entries = FakeEntryRepository()
        entries.debtLeg(1, 1, DebtSide.OWED_TO_ME, usd("500"))
        entries.debtLeg(2, 1, DebtSide.OWED_TO_ME, usd("-180"))

        val result = summary(listOf(cash(1, "0")), entries)().first()

        assertEquals(amounts(pocket = "0", owedToMe = "320", iOwe = "0", balance = "320"), result)
    }

    @Test
    fun aDebtBelowZeroInACurrencyWithNoRateDoesNotBlockTheSummary() = runTest {
        val entries = FakeEntryRepository()
        entries.debtLeg(1, 7, DebtSide.I_OWE, eur("-15"))

        val result = summary(listOf(cash(1, "100")), entries)().first()

        assertEquals(amounts("100", "0", "0", "100", debtsBelowZero = listOf(7L)), result)
    }

    @Test
    fun anOrdinaryAccountInACurrencyWithNoRateReportsTheMissingRate() = runTest {
        val result = summary(listOf(cash(1, "100"), cash(2, "40", currency = "EUR")))().first()

        assertEquals(HomeSummary.MissingRate("EUR"), result)
    }

    @Test
    fun theMissingRateTurnsIntoTheTotalsOnceItIsSet() = runTest {
        val fx = fakeFx(currencies, emptyList(), "USD")
        val home = summary(listOf(cash(1, "100"), cash(2, "40", currency = "EUR")), fx = fx)
        assertEquals(HomeSummary.MissingRate("EUR"), home().first())

        fx.emit(FxSnapshot("USD", mapOf("EUR" to BigDecimal("1.50")), currencies))

        assertEquals(amounts(pocket = "160", owedToMe = "0", iOwe = "0", balance = "160"), home().first())
    }

    @Test
    fun anArchivedCardInCreditIsOutOfThePocketAndNeedsNoRate() = runTest {
        val result = summary(listOf(cash(1, "100"), card(2, "60", currency = "EUR", archived = true)))().first()

        assertEquals(amounts(pocket = "100", owedToMe = "0", iOwe = "0", balance = "100"), result)
    }
}
