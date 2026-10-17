package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.recipe.IncomeRecipe
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.junit.Assert.*
import org.junit.Test

class IncomeFeeRecipeTest {
    private fun usd(value: String) = Money.of(value, "USD")
    private fun income() = EntryWorld.income(amount = "1000").copy(fee = usd("20"), feeCategoryId = 6)

    @Test fun salaryFeeReducesReceiptAndRecordsExpenseWithoutReducingGross() {
        val entry = income()
        val parts = IncomeRecipe.toLegs(entry)
        assertEquals(usd("980"), parts.legs.single { it.role == LegRole.ACCOUNT }.amount)
        assertEquals(usd("1000"), parts.legs.single { it.role == LegRole.MAIN }.amount)
        val fee = parts.legs.single { it.role == LegRole.FEE }
        assertEquals(usd("20"), fee.amount)
        assertEquals(LegTarget.Category(6, null), fee.target)
        assertEquals(TrackingRule.EXPENSE, IncomeRecipe.trackingRuleByRole[LegRole.FEE])
        assertEquals(entry, IncomeRecipe.fromLegs(parts))
    }

    @Test fun corruptedFeeOrReceiptCannotBeRead() {
        val parts = IncomeRecipe.toLegs(income())
        val invalid = listOf(
            parts.copy(legs = parts.legs.map { if (it.role == LegRole.ACCOUNT) it.copy(amount = usd("981")) else it }),
            parts.copy(legs = parts.legs.map { if (it.role == LegRole.FEE) it.copy(amount = usd("0")) else it }),
            parts.copy(legs = parts.legs.map { if (it.role == LegRole.FEE) it.copy(amount = usd("-20")) else it }),
            parts.copy(legs = parts.legs.map { if (it.role == LegRole.FEE) it.copy(target = LegTarget.Category(6, 7)) else it }),
            parts.copy(legs = parts.legs.map { if (it.role == LegRole.FEE) it.copy(amount = Money.of("20", "EUR")) else it }),
        )
        invalid.forEach { row ->
            val failure = runCatching { IncomeRecipe.fromLegs(row) }.exceptionOrNull()
            assertTrue("was $failure", failure is WalletException.UnreadableRow)
        }
    }

    @Test fun feeAtGrossIsUnreadableEvenWithMatchingZeroReceipt() {
        val parts = IncomeRecipe.toLegs(income())
        val row = parts.copy(legs = parts.legs.map {
            when (it.role) {
                LegRole.FEE -> it.copy(amount = usd("1000"))
                LegRole.ACCOUNT -> it.copy(amount = usd("0"))
                else -> it
            }
        })
        assertTrue(runCatching { IncomeRecipe.fromLegs(row) }.exceptionOrNull() is WalletException.UnreadableRow)
    }
    @Test fun feePlusLaterDeductionAboveGrossIsUnreadableEvenWhenReceiptBalances() {
        val line = org.aristonis.mywallet.domain.model.DeductionLine(
            itemId = 1, position = 0, name = "Tax",
            mode = org.aristonis.mywallet.domain.model.DeductionMode.FIXED,
            value = java.math.BigDecimal("990"), valueCurrencyCode = "USD",
            timing = org.aristonis.mywallet.domain.model.DeductionTiming.LATER,
            categoryId = 6, amount = usd("990"), debtId = 1,
        )
        val parts = IncomeRecipe.toLegs(income().copy(deductions = listOf(line)))
        assertTrue(runCatching { IncomeRecipe.fromLegs(parts) }.exceptionOrNull() is WalletException.UnreadableRow)
    }

    @Test fun feeWithADifferentStoredRateCannotBeRead() {
        val parts = IncomeRecipe.toLegs(income().copy(rateToBase = java.math.BigDecimal("1.25")))
        val row = parts.copy(legs = parts.legs.map {
            if (it.role == LegRole.FEE) it.copy(rateToBase = java.math.BigDecimal("1.26")) else it
        })

        val failure = runCatching { IncomeRecipe.fromLegs(row) }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.UnreadableRow)
    }

    @Test fun feeAndGrossMustBothHaveARateOrBothHaveNoRate() {
        for (mainRate in listOf(null, java.math.BigDecimal("1.25"))) {
            val parts = IncomeRecipe.toLegs(income().copy(rateToBase = mainRate))
            val feeRate = if (mainRate == null) java.math.BigDecimal("1.25") else null
            val row = parts.copy(legs = parts.legs.map {
                if (it.role == LegRole.FEE) it.copy(rateToBase = feeRate) else it
            })

            val failure = runCatching { IncomeRecipe.fromLegs(row) }.exceptionOrNull()

            assertTrue("main=$mainRate fee=$feeRate was $failure", failure is WalletException.UnreadableRow)
        }
    }

    @Test fun equivalentFeeRatesWithDifferentScaleRemainReadable() {
        val entry = income().copy(rateToBase = java.math.BigDecimal("1.25"))
        val parts = IncomeRecipe.toLegs(entry)
        val row = parts.copy(legs = parts.legs.map {
            if (it.role == LegRole.FEE) it.copy(rateToBase = java.math.BigDecimal("1.250")) else it
        })

        val decoded = IncomeRecipe.fromLegs(row)

        assertEquals(entry, decoded)
    }

}
