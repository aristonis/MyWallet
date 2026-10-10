package org.aristonis.mywallet.domain.model

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.time.LocalDate
import org.junit.Test

/** Pins how a [Debt] stores the name it was given and the key it derives from it. */
class DebtTest {

    @Test(expected = WalletException.NameBlank::class)
    fun debtWithBlankCounterpartyThrowsNameBlank() {
        Debt(counterparty = " ", side = DebtSide.I_OWE, currencyCode = "USD")
    }

    @Test
    fun debtKeyIsTheKeyOfItsCounterparty() {
        val debt = Debt(counterparty = " Ali ", side = DebtSide.OWED_TO_ME, currencyCode = "USD")

        assertEquals(NameKey.of("ali"), debt.key)
    }

    @Test
    fun counterpartyIsStoredTrimmed() {
        val debt = Debt(counterparty = "  Ali Baba ", side = DebtSide.I_OWE, currencyCode = "USD")

        assertEquals("Ali Baba", debt.counterparty)
    }

    @Test
    fun innerSpacingAsTypedIsKept() {
        val debt = Debt(counterparty = " Ali  Baba", side = DebtSide.I_OWE, currencyCode = "USD")

        assertEquals("Ali  Baba", debt.counterparty)
    }

    @Test
    fun renamingWithCopyTrimsTheNewNameAndMovesTheKey() {
        val debt = Debt(counterparty = "Ali", side = DebtSide.I_OWE, currencyCode = "USD")

        val renamed = debt.copy(counterparty = "  Omar ")

        assertEquals("Omar", renamed.counterparty)
        assertEquals(NameKey.of("omar"), renamed.key)
    }

    /**
     * Equality is written by hand, so every field must take part: a field left out would let two
     * different debts compare equal. Each copy below changes exactly one field.
     */
    @Test
    fun debtsAreEqualOnlyWhenEveryFieldMatches() {
        val debt = Debt(
            id = 3, counterparty = "Ali", side = DebtSide.I_OWE, currencyCode = "USD",
            dueDate = LocalDate.of(2026, 11, 1), note = "rent",
        )
        val same = debt.copy(counterparty = " Ali ")

        assertEquals(debt, same)
        assertEquals(debt.hashCode(), same.hashCode())
        listOf(
            debt.copy(id = 4),
            debt.copy(counterparty = "Omar"),
            debt.copy(side = DebtSide.OWED_TO_ME),
            debt.copy(currencyCode = "EUR"),
            debt.copy(dueDate = null),
            debt.copy(note = null),
        ).forEach { other -> assertNotEquals(debt, other) }
        assertNotEquals(debt, "Ali")
    }

    @Test
    fun copyWithNoChangesKeepsEveryField() {
        val debt = Debt(id = 3, counterparty = "Ali", side = DebtSide.I_OWE, currencyCode = "USD", note = "rent")

        assertEquals(debt, debt.copy())
        assertTrue(debt.toString().contains("counterparty=Ali"))
    }
}
