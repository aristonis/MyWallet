package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.ledger.recipe.pricedAsNew
import org.aristonis.mywallet.domain.service.RateBook
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/** Fees are expense category legs; transfer fees come out before the remaining money is converted. */
class FeeRecipesTest : DebtRecipeFixtures() {

    @Test
    fun transferFeeIsBookedAlongsideTheNetDestinationAmount() {
        val transfer = Transaction.Transfer(
            id = 50,
            sourceAccountId = 1,
            destAccountId = 2,
            sourceAmount = usd("300"),
            destAmount = usd("294"),
            rateUsed = BigDecimal.ONE,
            date = day,
            fee = usd("6"),
            feeCategoryId = 70,
            feeRateToBase = BigDecimal.ONE,
        )

        assertEquals(BigDecimal.ONE, transfer.toParts().header.pairRate)
        assertEquals(
            listOf(
                Leg(LegRole.SOURCE, 0, LegTarget.Account(1), usd("-300")),
                Leg(LegRole.DEST, 0, LegTarget.Account(2), usd("294")),
                category(LegRole.FEE, 70, null, usd("6"), BigDecimal.ONE),
            ),
            transfer.toParts().legs,
        )
        assertRoundTrip(transfer)
    }

    @Test
    fun expenseFeeIsAddedToWhatTheAccountPays() {
        val expense = Transaction.Expense(
            id = 51,
            accountId = 1,
            amount = usd("50"),
            categoryId = 6,
            date = day,
            fee = usd("1"),
            feeCategoryId = 70,
        )

        assertEquals(
            listOf(
                account(1, usd("-51")),
                category(LegRole.MAIN, 6, null, usd("50"), null),
                category(LegRole.FEE, 70, null, usd("1"), null),
            ),
            expense.toParts().legs,
        )
        assertRoundTrip(expense)
    }

    @Test
    fun crossCurrencyTransferConvertsOnlyTheAmountLeftAfterTheSendingCurrencyFee() {
        val transfer = Transaction.Transfer(
            id = 52,
            sourceAccountId = 1,
            destAccountId = 2,
            sourceAmount = usd("300"),
            destAmount = org.aristonis.mywallet.domain.model.Money.of("264.60", "EUR"),
            rateUsed = BigDecimal("0.9"),
            date = day,
            fee = usd("6"),
            feeCategoryId = 70,
            feeRateToBase = BigDecimal("1.1"),
        )

        assertEquals(
            listOf(
                Leg(LegRole.SOURCE, 0, LegTarget.Account(1), usd("-300")),
                Leg(LegRole.DEST, 0, LegTarget.Account(2), org.aristonis.mywallet.domain.model.Money.of("264.60", "EUR")),
                category(LegRole.FEE, 70, null, usd("6"), BigDecimal("1.1")),
            ),
            transfer.toParts().legs,
        )
        assertRoundTrip(transfer)
    }

    @Test
    fun aStoredTransferKeepsTheScaleOfItsDestinationAmount() {
        val transfer = Transaction.Transfer(
            id = 57,
            sourceAccountId = 1,
            destAccountId = 2,
            sourceAmount = usd("300"),
            destAmount = org.aristonis.mywallet.domain.model.Money.of("264.600", "EUR"),
            rateUsed = BigDecimal("0.9"),
            date = day,
            fee = usd("6"),
            feeCategoryId = 70,
        )

        assertEquals(transfer.destAmount.amount.toPlainString(), (read(transfer.toParts()) as Transaction.Transfer).destAmount.amount.toPlainString())
    }

    @Test
    fun aLargeTransferUsesItsStoredRoundedPairRateForBothPricingAndReadback() {
        val snapshot = FxSnapshot(
            baseCurrencyCode = "USD",
            ratesToBase = mapOf("EUR" to BigDecimal("3")),
            currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
        )
        val draft = Transaction.Transfer(
            id = 58,
            sourceAccountId = 1,
            destAccountId = 2,
            sourceAmount = org.aristonis.mywallet.domain.model.Money.of("1000000000000", "USD"),
            destAmount = org.aristonis.mywallet.domain.model.Money.of("1", "EUR"),
            rateUsed = BigDecimal.ONE,
            date = day,
        )

        val priced = draft.pricedAsNew("EUR", RateBook(snapshot))

        assertEquals(org.aristonis.mywallet.domain.model.Money.of("333333333333.00", "EUR"), priced.destAmount)
        assertRoundTrip(priced)
    }

    @Test
    fun expenseAndTransferPartsThatDoNotAddUpAreUnreadable() {
        val expenseParts = Transaction.Expense(
            id = 53, accountId = 1, amount = usd("50"), categoryId = 6, date = day,
            fee = usd("1"), feeCategoryId = 70,
        ).toParts()
        val transferParts = Transaction.Transfer(
            id = 54, sourceAccountId = 1, destAccountId = 2, sourceAmount = usd("300"),
            destAmount = usd("294"), rateUsed = BigDecimal.ONE, date = day,
            fee = usd("6"), feeCategoryId = 70,
        ).toParts()

        assertUnreadable(expenseParts.copy(legs = expenseParts.legs.map {
            if (it.role == LegRole.ACCOUNT) it.copy(amount = usd("-50")) else it
        }))
        assertUnreadable(transferParts.copy(legs = transferParts.legs.map {
            if (it.role == LegRole.DEST) it.copy(amount = usd("295")) else it
        }))
        assertUnreadable(transferParts.copy(legs = transferParts.legs.map {
            if (it.role == LegRole.FEE) it.copy(amount = usd("7")) else it
        }))
    }

    @Test
    fun examplesForEntriesWithFeesRoundTrip() {
        val entries = listOf(
            Transaction.Expense(
                id = 55, accountId = 1, amount = usd("50"), categoryId = 6, date = day,
                fee = usd("1"), feeCategoryId = 70,
            ),
            Transaction.Transfer(
                id = 56, sourceAccountId = 1, destAccountId = 2, sourceAmount = usd("300"),
                destAmount = usd("294"), rateUsed = BigDecimal.ONE, date = day,
                fee = usd("6"), feeCategoryId = 70,
            ),
        )

        entries.forEach(::assertRoundTrip)
    }
}
