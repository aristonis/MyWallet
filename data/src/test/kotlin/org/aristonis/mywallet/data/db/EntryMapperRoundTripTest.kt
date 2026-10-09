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
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.LocalDate
import org.junit.Test

/**
 * The rows written for an entry must read back as the same entry, with every amount's text intact,
 * and anything in the stored rows this build cannot turn into an entry must come out as the one
 * error the screens and the restore check know: an unreadable row naming its id and kind.
 */
class EntryMapperRoundTripTest {

    private val examples: List<Transaction> = EntryRecipes.all.flatMap { it.examples() }

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
