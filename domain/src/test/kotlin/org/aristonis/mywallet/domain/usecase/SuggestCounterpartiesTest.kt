package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.usecase.fake.FakeDebtRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestCounterpartiesTest {
    @Test fun normalizesThePrefixAndReturnsNamesOnceAcrossSidesAndCurrencies() = runTest {
        val debts = FakeDebtRepository(listOf(queryDebt(1).copy(counterparty = "Alice"),
            queryDebt(2, side = DebtSide.OWED_TO_ME).copy(counterparty = "ALICE"),
            queryDebt(3, "EUR").copy(counterparty = "Ali"), queryDebt(4).copy(counterparty = "Bob")))
        assertEquals(listOf("Ali", "Alice"), SuggestCounterparties(debts)(" ALI "))
    }

    @Test fun blankPrefixIncludesHiddenCounterparties() = runTest {
        val debts = FakeDebtRepository(listOf(queryDebt(1).copy(counterparty = "Bob"), queryDebt(2).copy(counterparty = "Ali")))
        assertEquals(listOf("Ali", "Bob"), SuggestCounterparties(debts)("  "))
    }

    @Test fun wildcardCharactersAreMatchedLiterally() = runTest {
        val debts = FakeDebtRepository(listOf(queryDebt(1).copy(counterparty = "a_b"), queryDebt(2).copy(counterparty = "axb"),
            queryDebt(3).copy(counterparty = "5%a"), queryDebt(4).copy(counterparty = "5xa")))
        val suggest = SuggestCounterparties(debts)
        assertEquals(listOf("a_b"), suggest("a_"))
        assertEquals(listOf("5%a"), suggest("5%"))
    }
}
