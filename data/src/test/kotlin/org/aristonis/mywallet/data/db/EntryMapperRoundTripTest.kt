package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.EntryRecipes
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.LocalDate
import java.math.BigDecimal
import org.junit.Test

/**
 * The rows written for an entry must read back as the same entry, with every amount's text intact,
 * and anything in the stored rows this build cannot turn into an entry must come out as the one
 * error the screens and the restore check know: an unreadable row naming its id and kind.
 */
class EntryMapperRoundTripTest {

    private val examples: List<Transaction> = EntryRecipes.all.flatMap { it.examples() }

    @Test
    fun incomeFeeAndPartialAndFullyDeferredExpensesKeepExactValuesInRows() {
        val date = LocalDate.of(2026, 10, 17)
        val entries = listOf(
            Transaction.Income(id = 501, accountId = 2, amount = Money.of("1000.00", "USD"),
                categoryId = 3, date = date, fee = Money.of("20.00", "USD"), feeCategoryId = 8),
            Transaction.Expense(id = 502, accountId = 2, amount = Money.of("1000.00", "USD"),
                categoryId = 4, subCategoryId = 5, date = date, fee = Money.of("3.00", "USD"),
                feeCategoryId = 8, owed = Money.of("700.00", "USD"), owedDebtId = 22),
            Transaction.Expense(id = 503, accountId = 2, amount = Money.of("1000.00", "USD"),
                categoryId = 4, date = date, owed = Money.of("1000.00", "USD"), owedDebtId = 22),
        )

        entries.forEach { entry ->
            val parts = entry.toParts()
            val rows = parts.toRows()
            val restored = rows.toTransaction(mapOf(22L to DebtSide.I_OWE))

            assertEquals(entry, restored)
            assertEquals(parts.legs.map { it.amount.amount.toPlainString() }, rows.legs.map { it.amount })
        }
    }

    @Test
    fun everyRecipeExampleMapsToRowsAndBackUnchanged() {
        examples.forEach { entry ->
            val parts = entry.toParts()
            // The side a debt leg carries is read back from its debt row; here the examples are that row.
            val sides = parts.legs.mapNotNull { it.target as? LegTarget.Debt }.associate { it.id to it.side }
            assertEquals(entry, parts.toRows().toTransaction(debtSides = sides))
        }
    }

    @Test
    fun incomeDeductionSnapshotsRoundTripBesideTheirJournalLegs() {
        val income = Transaction.Income(
            id = 401,
            accountId = 2,
            amount = Money.of("100", "USD"),
            categoryId = 3,
            date = LocalDate.of(2026, 10, 12),
            deductions = listOf(
                DeductionLine(
                    itemId = 9,
                    position = 0,
                    name = "Tax",
                    mode = DeductionMode.PERCENT,
                    value = BigDecimal("10"),
                    valueCurrencyCode = null,
                    timing = DeductionTiming.LATER,
                    categoryId = 5,
                    amount = Money.of("10", "USD"),
                    debtId = 22,
                ),
            ),
        )
        val parts = income.toParts()
        val stored = parts.toRows().copy(
            deductionLines = parts.deductionLines.map { it.toEntity(income.id) },
        )

        assertEquals(listOf(9L to "10"), stored.deductionLines.map { it.itemId to it.value })
        assertEquals(income, stored.toTransaction(debtSides = mapOf(22L to DebtSide.I_OWE)))
    }

    @Test
    fun aTransferStoredByVersion111ReadsBackAsStored() {
        // Base SYP, EUR 14321.75, USD 13250.5: 1.1.1 stored 286.44 with the pair rate rounded to 12 places,
        // which re-prices to 286.43. The stored row is read as it is.
        val transfer = Transaction.Transfer(
            id = 402,
            sourceAccountId = 1,
            destAccountId = 2,
            sourceAmount = Money.of("265.01", "EUR"),
            destAmount = Money.of("286.44", "USD"),
            rateUsed = BigDecimal("1.080846005811"),
            date = LocalDate.of(2026, 10, 12),
        )
        val stored = transfer.toParts().toRows()

        assertEquals(transfer, stored.toTransaction(emptyMap()))
    }

    @Test
    fun aTransferFeeKeepsItsRateInTheRows() {
        val transfer = Transaction.Transfer(
            id = 403,
            sourceAccountId = 1,
            destAccountId = 2,
            sourceAmount = Money.of("100000", "SYP"),
            destAmount = Money.of("9.4", "USD"),
            rateUsed = BigDecimal("0.0001"),
            date = LocalDate.of(2026, 10, 12),
            fee = Money.of("6000", "SYP"),
            feeCategoryId = 8,
            feeRateToBase = BigDecimal("0.0001"),
        )
        val stored = transfer.toParts().toRows()

        assertEquals("0.0001", stored.legs.single { it.role == "FEE" }.rateToBase)
        assertEquals(transfer, stored.toTransaction(emptyMap()))
    }

    @Test
    fun amountAndRateTextKeepTheirScale() {
        examples.forEach { entry ->
            val parts = entry.toParts()
            val rows = parts.toRows()
            assertEquals(parts.legs.map { it.amount.amount.toPlainString() }, rows.legs.map { it.amount })
            assertEquals(parts.legs.map { it.rateToBase?.toPlainString() }, rows.legs.map { it.rateToBase })
            assertEquals(parts.header.pairRate?.toPlainString(), rows.entry.pairRate)
        }
    }

    @Test
    fun anUnknownKindIsUnreadableAndNamesItsRow() {
        val rows = examples.first().toParts().toRows()
        val unknown = rows.copy(entry = rows.entry.copy(id = 41, kind = "REFUND_FROM_THE_FUTURE"))
        assertUnreadable(unknown, 41, "REFUND_FROM_THE_FUTURE")
    }

    @Test
    fun anUnknownRoleIsUnreadable() {
        val rows = examples.first().toParts().toRows()
        assertUnreadable(rows.copy(legs = rows.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(role = "NOT_A_ROLE") else leg }), rows)
    }

    @Test
    fun anAmountThatIsNotANumberIsUnreadable() {
        val rows = examples.first().toParts().toRows()
        assertUnreadable(rows.copy(legs = rows.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(amount = "12,5") else leg }), rows)
    }

    @Test
    fun aLegWithNoTargetOrTwoTargetsIsUnreadable() {
        val rows = examples.first().toParts().toRows()
        val none = rows.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(accountId = null, debtId = null, categoryId = null) else leg }
        assertUnreadable(rows.copy(legs = none), rows)
        val two = rows.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(accountId = 1, categoryId = 1) else leg }
        assertUnreadable(rows.copy(legs = two), rows)
    }

    @Test
    fun aDebtLegWhoseDebtRowIsMissingIsUnreadable() {
        val rows = examples.first().toParts().toRows()
        val onDebt = rows.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(accountId = null, debtId = 77) else leg }
        assertUnreadable(rows.copy(legs = onDebt), rows)
    }

    @Test
    fun legRolesAreStoredAsTheirFrozenNames() {
        val rows = examples.first { it is Transaction.Transfer }.toParts().toRows()
        assertEquals(listOf(LegRole.SOURCE.name, LegRole.DEST.name), rows.legs.map { it.role })
    }

    @Test
    fun aDebtLegStoresOnlyItsDebtAndGetsItsSideBackFromTheDebtRow() {
        listOf(DebtSide.I_OWE, DebtSide.OWED_TO_ME).forEach { side ->
            val parts = EntryParts(
                EntryHeader(3, "INCOME", LocalDate.of(2026, 2, 1), null),
                listOf(Leg(LegRole.DEBT, 0, LegTarget.Debt(5, side), Money.of("10.50", "USD"))),
            )

            val rows = parts.toRows()

            val leg = rows.legs.single()
            assertEquals(5L, leg.debtId)
            assertNull(leg.accountId)
            assertNull(leg.categoryId)
            assertEquals(parts, rows.toParts(debtSides = mapOf(5L to side)))
        }
    }

    @Test
    fun theMapperItselfRefusesBadStoredValuesWithoutAnyRecipe() {
        val rows = examples.first().toParts().toRows()
        val damaged = listOf(
            rows.copy(legs = rows.legs.mapIndexed { i, l -> if (i == 0) l.copy(role = "NOT_A_ROLE") else l }),
            rows.copy(legs = rows.legs.mapIndexed { i, l -> if (i == 0) l.copy(amount = "12,5") else l }),
            rows.copy(legs = rows.legs.mapIndexed { i, l -> if (i == 0) l.copy(accountId = null, debtId = null, categoryId = null) else l }),
            rows.copy(legs = rows.legs.mapIndexed { i, l -> if (i == 0) l.copy(accountId = 1, categoryId = 1) else l }),
            rows.copy(legs = rows.legs.mapIndexed { i, l -> if (i == 0) l.copy(accountId = null, debtId = 77) else l }),
            rows.copy(legs = rows.legs.mapIndexed { i, l -> if (i == 0) l.copy(accountId = null, categoryId = null, subCategoryId = 4) else l }),
        )
        damaged.forEach { bad ->
            val error = assertThrows(WalletException.UnreadableRow::class.java) { bad.toParts(debtSides = emptyMap()) }
            assertEquals(rows.entry.id, error.id)
            assertTrue(error.cause is IllegalArgumentException)
        }
    }

    private fun assertUnreadable(rows: StoredEntry, original: StoredEntry) =
        assertUnreadable(rows, original.entry.id, original.entry.kind)

    private fun assertUnreadable(rows: StoredEntry, id: Long, kind: String) {
        val error = assertThrows(WalletException.UnreadableRow::class.java) { rows.toTransaction(debtSides = emptyMap()) }
        assertEquals(id, error.id)
        assertEquals(kind, error.kind)
    }
}
