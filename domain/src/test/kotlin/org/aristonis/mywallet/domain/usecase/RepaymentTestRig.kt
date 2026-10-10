package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import java.math.BigDecimal

internal class RepaymentTestRig(
    initialEntries: List<Transaction> = listOf(opening()),
    debts: List<Debt> = listOf(DebtEntryWorld.ali, DebtEntryWorld.sara),
    accounts: List<Account> = listOf(EntryWorld.usdAccount, EntryWorld.sypAccount),
    savedRates: Map<String, String> = emptyMap(),
    base: String = "USD",
    faultAfterWrite: Boolean = false,
    faultAfterFill: Boolean = false,
) {
    val store = SaveEntryRig(initialEntries = initialEntries, initialDebts = debts, accounts = accounts,
        savedRates = savedRates, base = base, faultAfterWrite = faultAfterWrite,
        faultAfterFill = faultAfterFill, atomic = true)
    val record = RecordRepayment(runner = store.runner, debts = store.debts, ledger = store.ledger,
        entries = store.entries, accounts = store.accountRepository,
        fx = fakeFx(EntryWorld.currencies, savedRates.map { (code, rate) -> ExchangeRate(code, BigDecimal(rate)) }, base),
        saveEntry = store.saveEntry)
    val writeOff = WriteOffDebt(runner = store.runner, debts = store.debts, ledger = store.ledger, saveEntry = store.saveEntry)

    suspend fun remaining(debtId: Long = 7): Money =
        DebtRemaining.byDebt(store.ledger.debtLegs(setOf(debtId))).getValue(debtId).remaining

    suspend fun saved(id: Long) = store.entries.findById(id) as Transaction.Repayment

    companion object {
        val day = EntryWorld.day
        fun opening(id: Long = 1, debtId: Long = 7, side: DebtSide = DebtSide.I_OWE,
            amount: String = "5000", currency: String = "USD") = Transaction.OwedBefore(
            id = id, debtId = debtId, side = side, amount = Money.of(amount, currency), date = day)
        fun usd(amount: String) = Money.of(amount, "USD")
        fun syp(amount: String) = Money.of(amount, "SYP")
    }
}
