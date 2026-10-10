package org.aristonis.mywallet.domain.usecase.fake

import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction

object DebtEntryWorld {
    val ali = Debt(id = 7, counterparty = "Ali", side = DebtSide.I_OWE, currencyCode = "USD")
    val sara = Debt(id = 8, counterparty = "Sara", side = DebtSide.OWED_TO_ME, currencyCode = "USD")
    fun usd(amount: String) = Money.of(amount, "USD")
    fun borrow(id: Long = 0, debtId: Long = 7, amount: String = "100") =
        Transaction.Borrow(id, 1, debtId, usd(amount), EntryWorld.day)
    fun lend(id: Long = 0, debtId: Long = 8, amount: String = "100") =
        Transaction.Lend(id, 1, debtId, usd(amount), EntryWorld.day)
    fun repayment(id: Long = 0, debtId: Long = 7, side: DebtSide = DebtSide.I_OWE, amount: String = "50") =
        Transaction.Repayment(id, side, 1, debtId, usd(amount), usd(amount), date = EntryWorld.day)
}
