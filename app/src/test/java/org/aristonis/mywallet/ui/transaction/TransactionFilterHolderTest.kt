package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import org.aristonis.mywallet.domain.model.TransactionFilter
import org.aristonis.mywallet.domain.model.TypeGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionFilterHolderTest {
    @Test fun `fresh handle starts with all entries`() {
        assertEquals(TransactionFilter(), TransactionFilterHolder(SavedStateHandle()).filter.value)
    }

    @Test fun `saved handle restores every filter and clear resets all terms`() {
        val handle = SavedStateHandle()
        val holder = TransactionFilterHolder(handle)
        holder.toggleType(TypeGroup.TRANSFER)
        holder.toggleAccount(3)
        holder.toggleCategory(9)
        assertEquals(TransactionFilter(setOf(TypeGroup.TRANSFER), setOf(3), setOf(9)), TransactionFilterHolder(handle).filter.value)
        holder.clear()
        assertEquals(TransactionFilter(), holder.filter.value)
        assertEquals(TransactionFilter(), TransactionFilterHolder(handle).filter.value)
    }
    @Test fun `multiple selections persist and toggling the last off makes its group unrestricted`() {
        val handle = SavedStateHandle()
        val holder = TransactionFilterHolder(handle)
        holder.toggleType(TypeGroup.INCOME)
        holder.toggleType(TypeGroup.EXPENSE)
        holder.toggleAccount(1)
        holder.toggleAccount(2)
        holder.toggleCategory(20)
        holder.toggleCategory(21)
        assertEquals(TransactionFilter(setOf(TypeGroup.INCOME, TypeGroup.EXPENSE), setOf(1, 2), setOf(20, 21)),
            TransactionFilterHolder(handle).filter.value)
        holder.toggleType(TypeGroup.INCOME)
        holder.toggleType(TypeGroup.EXPENSE)
        holder.toggleAccount(1)
        holder.toggleAccount(2)
        holder.toggleCategory(20)
        holder.toggleCategory(21)
        assertEquals(TransactionFilter(), holder.filter.value)
    }

    @Test fun `All clears just types and group clears preserve the other selections`() {
        val holder = TransactionFilterHolder(SavedStateHandle())
        holder.toggleType(TypeGroup.INCOME)
        holder.toggleAccount(1)
        holder.toggleCategory(20)
        holder.toggleType(TypeGroup.ALL)
        assertEquals(TransactionFilter(accountIds = setOf(1), categoryIds = setOf(20)), holder.filter.value)
        holder.clearAccounts()
        assertEquals(setOf(20L), holder.filter.value.categoryIds)
        holder.clearCategories()
        assertEquals(TransactionFilter(), holder.filter.value)
    }

}
