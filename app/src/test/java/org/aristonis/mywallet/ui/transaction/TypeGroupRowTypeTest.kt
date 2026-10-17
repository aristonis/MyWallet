package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.ledger.EntryRecipes
import org.aristonis.mywallet.domain.model.TypeGroup
import org.aristonis.mywallet.domain.service.TransactionFilterMatch
import org.junit.Assert.assertEquals
import org.junit.Test

class TypeGroupRowTypeTest {
    @Test fun everyDisplayRowTypeMapsToItsChip() {
        val expected = mapOf(
            TransactionRowType.INCOME to TypeGroup.INCOME,
            TransactionRowType.EXPENSE to TypeGroup.EXPENSE,
            TransactionRowType.REFUND to TypeGroup.EXPENSE,
            TransactionRowType.TRANSFER to TypeGroup.TRANSFER,
            TransactionRowType.BORROW to TypeGroup.DEBTS,
            TransactionRowType.LEND to TypeGroup.DEBTS,
            TransactionRowType.OWED_BEFORE to TypeGroup.DEBTS,
            TransactionRowType.CREDIT_PURCHASE to TypeGroup.EXPENSE,
            TransactionRowType.EARNED_UNPAID to TypeGroup.INCOME,
            TransactionRowType.REPAYMENT_PAID to TypeGroup.DEBTS,
            TransactionRowType.REPAYMENT_RECEIVED to TypeGroup.DEBTS,
            TransactionRowType.WRITE_OFF_LOSS to TypeGroup.EXPENSE,
            TransactionRowType.WRITE_OFF_FORGIVEN to TypeGroup.INCOME,
        )
        assertEquals(TransactionRowType.entries.toSet(), expected.keys)
        expected.forEach { (row, group) -> assertEquals(group, row.typeGroup()) }
    }

    @Test fun rowAndStoredKindMappingsAgreeForEveryRecipeExample() {
        EntryRecipes.all.flatMap { it.examples() }.forEach { entry ->
            assertEquals(TransactionFilterMatch.typeGroup(entry), entry.rowType().typeGroup())
        }
    }
}
