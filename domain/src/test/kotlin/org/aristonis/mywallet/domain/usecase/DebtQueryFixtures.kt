package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction

import java.math.BigDecimal
import java.time.LocalDate

internal val queryDay: LocalDate = LocalDate.of(2026, 10, 10)
internal fun queryDebt(id: Long, code: String = "USD", side: DebtSide = DebtSide.I_OWE, due: LocalDate? = null) =
    Debt(id, "Person $id", side, code, due)
internal fun opening(debt: Debt, amount: String, id: Long = debt.id) =
    Transaction.OwedBefore(id, debt.id, debt.side, Money.of(amount, debt.currencyCode), queryDay)
internal fun queryFx(rates: Map<String, BigDecimal> = emptyMap()) = FxSnapshot(
    "USD", rates, listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("JPY", "¥", 0)),
)
