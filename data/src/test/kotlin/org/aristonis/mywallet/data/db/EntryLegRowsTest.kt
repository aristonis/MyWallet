package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The entry reads return one flat row per leg, with the header repeated beside it. These rows must
 * fold back into whole entries in the order the query gave them, and a row the app cannot read must
 * stop the read naming that entry rather than be skipped: a skipped entry would leave every balance
 * and total silently wrong.
 */
class EntryLegRowsTest {

    private val date = LocalDate.of(2026, 8, 1)

    private fun header(entryId: Long, kind: String, pairRate: String? = null) = EntryLegRow(
        entryId = entryId, kind = kind, date = date, note = "n$entryId", pairRate = pairRate,
        legId = null, role = null, position = null, accountId = null, debtId = null, categoryId = null,
        subCategoryId = null, amount = null, currencyCode = null, rateToBase = null, debtSide = null,
    )

    private fun EntryLegRow.leg(
        legId: Long,
        role: String,
        amount: String,
        accountId: Long? = null,
        categoryId: Long? = null,
        rate: String? = null,
        currency: String = "USD",
    ) = copy(
        legId = legId, role = role, position = 0, accountId = accountId, categoryId = categoryId,
        amount = amount, currencyCode = currency, rateToBase = rate,
    )

    private fun expenseRows(entryId: Long, amount: String): List<EntryLegRow> {
        val head = header(entryId, "EXPENSE")
        return listOf(
            head.leg(entryId * 10, "ACCOUNT", "-$amount", accountId = 1),
            head.leg(entryId * 10 + 1, "MAIN", amount, categoryId = 3, rate = "1"),
        )
    }

    @Test
    fun rowsFoldIntoEntriesInTheOrderTheQueryReturnedThem() {
        val transfer = header(7, "TRANSFER", pairRate = "0.92").let {
            listOf(
                it.leg(71, "SOURCE", "-20.000", accountId = 1),
                it.leg(72, "DEST", "18.40", accountId = 2, currency = "EUR"),
            )
        }

        val entries = (expenseRows(9, "12.50") + transfer + expenseRows(2, "1")).toTransactions()

        assertEquals(listOf(9L, 7L, 2L), entries.map { it.id })
        assertEquals(
            Transaction.Expense(
                id = 9, accountId = 1, amount = Money.of("12.50", "USD"), categoryId = 3, date = date,
                note = "n9", rateToBase = BigDecimal.ONE,
            ),
            entries[0],
        )
        assertEquals(
            Transaction.Transfer(
                id = 7, sourceAccountId = 1, destAccountId = 2, sourceAmount = Money.of("20.000", "USD"),
                destAmount = Money.of("18.40", "EUR"), rateUsed = BigDecimal("0.92"), date = date, note = "n7",
            ),
            entries[1],
        )
    }

    @Test
    fun noRowsAreNoEntries() {
        assertEquals(emptyList<Transaction>(), emptyList<EntryLegRow>().toTransactions())
    }

    @Test
    fun anEntryOfAKindNoRecipeReadsFailsTheReadNamingItsRow() {
        // The kind a row from a newer build, or a hand-edited backup, would carry.
        val rows = listOf(header(5, "LOAN").leg(51, "ACCOUNT", "5", accountId = 1))

        val failure = assertThrows(WalletException.UnreadableRow::class.java) { rows.toTransactions() }

        assertEquals(5L, failure.id)
        assertEquals("LOAN", failure.kind)
    }

    @Test
    fun anOldRowKeptOnlyAsARawCopyHasNoLegsAndFailsTheReadNamingItsRow() {
        val failure = assertThrows(WalletException.UnreadableRow::class.java) {
            listOf(header(14, "LEGACY_UNREADABLE")).toTransactions()
        }

        assertEquals(14L, failure.id)
        assertEquals("LEGACY_UNREADABLE", failure.kind)
    }
}
