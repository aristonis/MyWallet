package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DebtSide.I_OWE
import org.aristonis.mywallet.domain.model.DebtSide.OWED_TO_ME
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

/** Shared entries and helpers for the debt recipe tests; the test classes extend it. */
abstract class DebtRecipeFixtures {

    protected val day: LocalDate = LocalDate.of(2026, 5, 20)

    protected fun usd(a: String) = Money.of(a, "USD")
    protected fun syp(a: String) = Money.of(a, "SYP")

    protected fun account(id: Long, amount: Money) = Leg(LegRole.ACCOUNT, 0, LegTarget.Account(id), amount)
    protected fun debt(id: Long, side: DebtSide, amount: Money) = Leg(LegRole.DEBT, 0, LegTarget.Debt(id, side), amount)
    protected fun category(role: LegRole, id: Long, sub: Long?, amount: Money, rate: BigDecimal?) =
        Leg(role, 0, LegTarget.Category(id, sub), amount, rate)

    @Suppress("UNCHECKED_CAST")
    protected fun read(parts: EntryParts): Transaction =
        (EntryRecipes.forKind(parts.header.kind)!! as EntryRecipe<Transaction>).fromLegs(parts)

    protected fun assertRoundTrip(entry: Transaction) {
        assertEquals(entry, read(entry.toParts()))
    }

    protected fun assertUnreadable(parts: EntryParts) {
        val error = assertThrows(WalletException.UnreadableRow::class.java) { read(parts) }
        assertEquals(parts.header.id, error.id)
        assertEquals(parts.header.kind, error.kind)
    }

    protected fun withDebtSide(parts: EntryParts, side: DebtSide) = parts.copy(
        legs = parts.legs.map {
            val target = it.target
            if (target is LegTarget.Debt) it.copy(target = target.copy(side = side)) else it
        },
    )

    protected fun withRole(parts: EntryParts, from: LegRole, to: LegRole) =
        parts.copy(legs = parts.legs.map { if (it.role == from) it.copy(role = to) else it })

    protected val borrow = Transaction.Borrow(id = 1, accountId = 1, debtId = 10, amount = usd("5000"), date = day, note = "Loan")
    protected val lend = Transaction.Lend(id = 2, accountId = 1, debtId = 11, amount = usd("300"), date = day)
    protected val creditPurchase = Transaction.CreditPurchase(
        id = 4, debtId = 12, amount = usd("120.50"), categoryId = 4, subCategoryId = 5,
        date = day, note = "Laptop bag", rateToBase = BigDecimal.ONE,
    )
    protected val earnedUnpaid = Transaction.EarnedUnpaid(
        id = 5, debtId = 13, amount = usd("800"), categoryId = 1, date = day, rateToBase = BigDecimal.ONE,
    )
    protected val repaymentPaying = Transaction.Repayment(
        id = 6, side = I_OWE, accountId = 1, debtId = 10, accountAmount = usd("450"), principal = usd("400"),
        interest = usd("50"), interestCategoryId = 7, interestRateToBase = BigDecimal.ONE, date = day,
    )
    protected val repaymentReceiving = Transaction.Repayment(
        id = 7, side = OWED_TO_ME, accountId = 1, debtId = 11, accountAmount = usd("450"), principal = usd("400"),
        interest = usd("50"), interestCategoryId = 8, interestSubCategoryId = 9, interestRateToBase = BigDecimal.ONE,
        date = day, note = "With interest",
    )
    protected val repaymentCrossCurrency = Transaction.Repayment(
        id = 8, side = I_OWE, accountId = 2, debtId = 10, accountAmount = syp("1300000"), principal = usd("125"),
        interest = syp("50000"), interestCategoryId = 7, interestRateToBase = BigDecimal("0.00008"),
        pairRate = BigDecimal("0.0001"), date = day,
    )
    protected val writeOffLoss = Transaction.WriteOff(
        id = 9, side = OWED_TO_ME, debtId = 11, amount = usd("75"), categoryId = 20, rateToBase = BigDecimal.ONE, date = day,
    )
    protected val writeOffForgiven = Transaction.WriteOff(
        id = 10, side = I_OWE, debtId = 10, amount = usd("60"), categoryId = 21, subCategoryId = 22,
        rateToBase = BigDecimal.ONE, date = day,
    )
}
