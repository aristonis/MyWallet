package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.DebtAmounts
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DebtAmountsFromHistoryPaidLaterTest {
    private val day = LocalDate.of(2026, 10, 17)
    private fun usd(value: String) = Money.of(value, "USD")
    private fun eur(value: String) = Money.of(value, "EUR")

    @Test fun paidLaterHistorySeparatesOriginalPrincipalAndWriteOffs() {
        val history = listOf(
            Transaction.Expense(1, 1, usd("1000"), 7, date = day,
                owed = usd("700"), owedDebtId = 1),
            Transaction.Borrow(2, 1, 1, usd("10"), day),
            Transaction.Lend(3, 1, 1, usd("20"), day),
            Transaction.OwedBefore(4, 1, DebtSide.I_OWE, usd("30"), day),
            Transaction.CreditPurchase(5, 1, usd("40"), 7, date = day),
            Transaction.EarnedUnpaid(6, 1, usd("50"), 7, date = day),
            Transaction.Repayment(7, DebtSide.I_OWE, 1, 1, usd("102"), usd("100"),
                interest = usd("2"), date = day),
            Transaction.WriteOff(8, DebtSide.I_OWE, 1, usd("5"), 7, date = day),
        )

        val amounts = DebtAmountsFromHistory.calculate(1, "USD", history)

        assertEquals(DebtAmounts(usd("850"), usd("100"), usd("5")), amounts)
    }

    @Test fun unrelatedDebtsAndEntriesNeverContributeEvenWhenTheirCurrencyDiffers() {
        val history = listOf(
            Transaction.Expense(1, 1, eur("1000"), 7, date = day,
                owed = eur("700"), owedDebtId = 2),
            Transaction.Expense(2, 1, eur("20"), 7, date = day),
            Transaction.Borrow(3, 1, 2, eur("10"), day),
            Transaction.Lend(4, 1, 2, eur("20"), day),
            Transaction.OwedBefore(5, 2, DebtSide.I_OWE, eur("30"), day),
            Transaction.CreditPurchase(6, 2, eur("40"), 7, date = day),
            Transaction.EarnedUnpaid(7, 2, eur("50"), 7, date = day),
            Transaction.Repayment(8, DebtSide.I_OWE, 1, 2, eur("100"), eur("100"), date = day),
            Transaction.WriteOff(9, DebtSide.I_OWE, 2, eur("5"), 7, date = day),
            Transaction.Income(10, 1, eur("100"), 7, date = day),
            Transaction.Refund(11, 1, eur("10"), 7, date = day),
            Transaction.Transfer(12, 1, 2, eur("10"), eur("10"), BigDecimal.ONE, day),
        )

        val amounts = DebtAmountsFromHistory.calculate(1, "USD", history)

        assertEquals(DebtAmounts.zero("USD"), amounts)
    }
}
