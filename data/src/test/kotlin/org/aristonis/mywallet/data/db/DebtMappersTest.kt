package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

/**
 * The debt row mappers: a debt survives the trip to a row and back, and a row with a blank name or a
 * key that is not the one recomputed from its name is unreadable.
 */
class DebtMappersTest {

    private val due = LocalDate.of(2026, 3, 15)

    @Test
    fun aDebtSurvivesTheTripToARowAndBack() {
        val debt = Debt(id = 7, counterparty = "Ali", side = DebtSide.I_OWE, currencyCode = "USD", dueDate = due, note = "rent")

        assertEquals(debt, debt.toEntity().toDomain())
    }

    @Test
    fun theRowKeepsTheTrimmedNameAndTheKeyOfTheName() {
        val row = Debt(id = 7, counterparty = "  Ali  Khan ", side = DebtSide.OWED_TO_ME, currencyCode = "EUR").toEntity()

        assertEquals("Ali  Khan", row.counterparty)
        assertEquals("ali khan", row.counterpartyKey)
        assertEquals("OWED_TO_ME", row.side)
        assertEquals("EUR", row.currencyCode)
    }

    @Test
    fun aRowWithNoDueDateOrNoteReadsBackWithNone() {
        val read = Debt(id = 1, counterparty = "Ali", side = DebtSide.I_OWE, currencyCode = "USD").toEntity().toDomain()

        assertEquals(null, read.dueDate)
        assertEquals(null, read.note)
    }

    @Test
    fun aBlankStoredNameIsAnUnreadableDebtRowNamingTheRow() {
        val row = DebtEntity(id = 42, counterparty = "   ", counterpartyKey = "", side = "I_OWE", currencyCode = "USD")

        val thrown = assertThrows(WalletException.UnreadableRow::class.java) { row.toDomain() }

        assertEquals(42L, thrown.id)
        assertEquals("DEBT", thrown.kind)
    }

    @Test
    fun aStoredKeyThatIsNotTheRecomputedKeyIsAnUnreadableDebtRow() {
        val row = DebtEntity(id = 43, counterparty = "Ali", counterpartyKey = "someone else", side = "I_OWE", currencyCode = "USD")

        val thrown = assertThrows(WalletException.UnreadableRow::class.java) { row.toDomain() }

        assertEquals(43L, thrown.id)
        assertEquals("DEBT", thrown.kind)
    }

    @Test
    fun aStoredKeyThatIsNotFoldedTheSameWayIsUnreadable() {
        val row = DebtEntity(id = 44, counterparty = "Ali", counterpartyKey = "Ali", side = "I_OWE", currencyCode = "USD")

        assertThrows(WalletException.UnreadableRow::class.java) { row.toDomain() }
    }
}
