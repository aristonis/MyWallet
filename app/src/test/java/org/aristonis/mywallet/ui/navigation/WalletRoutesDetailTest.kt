package org.aristonis.mywallet.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The routes the account and transaction detail pages add, and the add form's origin arguments. */
class WalletRoutesDetailTest {

    private val tab = WalletTab.HOME

    @Test
    fun `the add pattern carries debt side, account and pay card and still starts at the add leaf`() {
        val pattern = tab.addTransactionPattern()

        assertTrue(pattern.startsWith(tab.child(Leaf.ADD_TRANSACTION)))
        assertTrue(pattern.contains("$ARG_DEBT_SIDE={$ARG_DEBT_SIDE}"))
        assertTrue(pattern.contains("$ARG_ACCOUNT_ID={$ARG_ACCOUNT_ID}"))
        assertTrue(pattern.contains("$ARG_PAY_CARD={$ARG_PAY_CARD}"))
    }

    @Test
    fun `add for account and pay card build the exact routes`() {
        assertEquals("home/addTransaction?accountId=7", tab.addForAccountRoute(7))
        assertEquals("home/addTransaction?accountId=7&payCard=true", tab.payCardRoute(7))
    }

    @Test
    fun `add for account and pay card hide the navigation bar and belong to home`() {
        listOf(tab.addForAccountRoute(7), tab.payCardRoute(7)).forEach { route ->
            assertFalse(route, showsNavigationBar(route))
            assertEquals(route, WalletTab.HOME, tabOf(route))
        }
    }

    @Test
    fun `the detail leaves take an id`() {
        assertTrue(Leaf.ACCOUNT_DETAIL in Leaf.withId)
        assertTrue(Leaf.TRANSACTION_DETAIL in Leaf.withId)
    }

    @Test
    fun `the detail patterns and routes have the expected shape`() {
        assertEquals("home/accountDetail/{id}", tab.childPattern(Leaf.ACCOUNT_DETAIL))
        assertEquals("transactions/transactionDetail/{id}",
            WalletTab.TRANSACTIONS.childPattern(Leaf.TRANSACTION_DETAIL))
        assertEquals("home/accountDetail/3", tab.child(Leaf.ACCOUNT_DETAIL, 3))
    }

    @Test
    fun `the detail routes are not top level and hide the navigation bar`() {
        listOf(tab.child(Leaf.ACCOUNT_DETAIL, 3), WalletTab.TRANSACTIONS.child(Leaf.TRANSACTION_DETAIL, 5))
            .forEach { route ->
                assertFalse(route, isTopLevel(route))
                assertFalse(route, showsNavigationBar(route))
            }
    }

    @Test
    fun `no two leaves share a name`() {
        val leaves = Leaf::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
            .map { it.get(null) as String }

        assertTrue(leaves.size > 10)
        assertEquals(leaves.size, leaves.toSet().size)
    }
}
