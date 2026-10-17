package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.model.DebtSide.I_OWE
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/** Income deductions keep their category history and any owed-later debt legs on the entry. */
class IncomeDeductionRecipeTest : DebtRecipeFixtures() {

    @Test
    fun aDeductionOnAParentAndSubCategoryRoundTripsWithItsCategoryLeg() {
        val original = salaryWithDeductions()
        val income = original.copy(deductions = original.deductions.map {
            if (it.position == 2) it.copy(subCategoryId = 24) else it
        })

        assertEquals(
            LegTarget.Category(23, 24),
            income.toParts().legs.single { it.role == LegRole.DEDUCTION && it.position == 2 }.target,
        )
        assertRoundTrip(income)
    }

    @Test
    fun aDeductionLegWithAnUnrecordedSubCategoryIsUnreadable() {
        val parts = salaryWithDeductions().toParts()
        val changed = parts.copy(legs = parts.legs.map { leg ->
            if (leg.role == LegRole.DEDUCTION && leg.position == 2) {
                leg.copy(target = LegTarget.Category(23, 24))
            } else leg
        })

        assertUnreadable(changed)
    }


    @Test
    fun salaryStoresGrossAndEachDeductionWithDebtLegsForOwedLaterLines() {
        val income = salaryWithDeductions()
        val parts = income.toParts()

        assertEquals(income.deductions, parts.deductionLines)
        assertEquals(
            listOf(
                account(1, usd("980")),
                category(LegRole.MAIN, 1, null, usd("1000"), BigDecimal.ONE),
                Leg(LegRole.DEDUCTION, 0, LegTarget.Category(21, null), usd("100"), BigDecimal.ONE),
                Leg(LegRole.DEDUCTION_DEBT, 0, LegTarget.Debt(31, I_OWE), usd("100")),
                Leg(LegRole.DEDUCTION, 1, LegTarget.Category(22, null), usd("50"), BigDecimal.ONE),
                Leg(LegRole.DEDUCTION_DEBT, 1, LegTarget.Debt(32, I_OWE), usd("50")),
                Leg(LegRole.DEDUCTION, 2, LegTarget.Category(23, null), usd("20"), BigDecimal.ONE),
            ),
            parts.legs,
        )
        assertRoundTrip(income)
    }

    @Test
    fun oneHundredPercentPaidNowIncomeMayLeaveTheAccountAtZero() {
        val income = Transaction.Income(
            id = 41,
            accountId = 1,
            amount = usd("100"),
            categoryId = 1,
            date = day,
            rateToBase = BigDecimal.ONE,
            deductions = listOf(
                line(itemId = 10, position = 0, name = "Tax", categoryId = 21, amount = "100", timing = DeductionTiming.NOW),
            ),
        )

        assertEquals(usd("0"), income.toParts().legs.single { it.role == LegRole.ACCOUNT }.amount)
        assertRoundTrip(income)
    }

    @Test
    fun deductionLinesMustHaveMatchingCategoryAndOwedLaterDebtLegs() {
        val parts = salaryWithDeductions().toParts()
        val missingDebtLeg = parts.copy(legs = parts.legs.filterNot { it.role == LegRole.DEDUCTION_DEBT && it.position == 1 })
        val missingCategoryLeg = parts.copy(legs = parts.legs.filterNot { it.role == LegRole.DEDUCTION && it.position == 2 })
        val wrongDebtSide = parts.copy(legs = parts.legs.map {
            if (it.role == LegRole.DEDUCTION_DEBT && it.position == 0) it.copy(target = LegTarget.Debt(31, org.aristonis.mywallet.domain.model.DebtSide.OWED_TO_ME)) else it
        })
        val lostLineSnapshot = parts.copy(deductionLines = parts.deductionLines.dropLast(1))

        assertUnreadable(missingDebtLeg)
        assertUnreadable(missingCategoryLeg)
        assertUnreadable(wrongDebtSide)
        assertUnreadable(lostLineSnapshot)
    }

    @Test
    fun deductionEntryExamplesRoundTripWithTheirRecordedLines() {
        assertRoundTrip(salaryWithDeductions())
    }

    private fun salaryWithDeductions() = Transaction.Income(
        id = 40,
        accountId = 1,
        amount = usd("1000"),
        categoryId = 1,
        date = day,
        rateToBase = BigDecimal.ONE,
        deductions = listOf(
            line(itemId = 10, position = 0, name = "Tax", categoryId = 21, amount = "100", debtId = 31),
            line(itemId = 11, position = 1, name = "Family", categoryId = 22, amount = "50", debtId = 32),
            line(itemId = 12, position = 2, name = "Bills", categoryId = 23, amount = "20", timing = DeductionTiming.NOW),
        ),
    )

    private fun line(
        itemId: Long,
        position: Int,
        name: String,
        categoryId: Long,
        amount: String,
        debtId: Long? = null,
        timing: DeductionTiming = DeductionTiming.LATER,
    ) = DeductionLine(
        itemId = itemId,
        position = position,
        name = name,
        mode = DeductionMode.FIXED,
        value = BigDecimal(amount),
        valueCurrencyCode = "USD",
        timing = timing,
        categoryId = categoryId,
        amount = usd(amount),
        debtId = debtId,
    )
}
