package org.aristonis.mywallet.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each tab owns its own copy of the supporting screens it can reach, so opening Exchange rates from
 * Home and opening it from Settings are two different destinations. That is what lets Back return to
 * the tab you actually came from, and what lets a tab keep its own history while you visit another.
 */
class WalletRoutesTest {

    @Test
    fun deductionsListKeepsNavigationAndEditorsHideIt() {
        val tab = WalletTab.SETTINGS
        assertEquals("settings/deductions", tab.child(Leaf.DEDUCTIONS))
        assertTrue(showsNavigationBar(tab.child(Leaf.DEDUCTIONS)))
        assertTrue(!showsNavigationBar(tab.child(Leaf.ADD_DEDUCTION)))
        assertTrue(!showsNavigationBar(tab.child(Leaf.EDIT_DEDUCTION, 3)))
        assertEquals("settings/editDeduction/{id}", tab.childPattern(Leaf.EDIT_DEDUCTION))
    }


    @Test
    fun `every tab has a distinct route`() {
        val routes = WalletTab.entries.map { it.route }

        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `a tab's root sits inside that tab's graph`() {
        WalletTab.entries.forEach { tab ->
            assertTrue("${tab.rootRoute} should start with ${tab.route}", tab.rootRoute.startsWith("${tab.route}/"))
        }
    }

    @Test
    fun `the same supporting screen is a different destination in each tab`() {
        val fromHome = WalletTab.HOME.child(Leaf.RATES)
        val fromSettings = WalletTab.SETTINGS.child(Leaf.RATES)

        assertEquals("home/rates", fromHome)
        assertEquals("settings/rates", fromSettings)
    }

    @Test
    fun `no two destinations in the whole graph collide`() {
        val all = WalletTab.entries.flatMap { tab ->
            listOf(tab.route, tab.rootRoute) +
                Leaf.all.map { tab.child(it) } +
                Leaf.withId.map { tab.childPattern(it) }
        }

        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `an editor route carries its id and matches its own pattern`() {
        val pattern = WalletTab.TRANSACTIONS.childPattern(Leaf.EDIT_TRANSACTION)
        val route = WalletTab.TRANSACTIONS.child(Leaf.EDIT_TRANSACTION, id = 42)

        assertEquals("transactions/editTransaction/{$ARG_ID}", pattern)
        assertEquals("transactions/editTransaction/42", route)
        assertEquals(route, pattern.replace("{$ARG_ID}", "42"))
    }

    /** Ids are Long: a wallet that has churned through rows will hand out values past Int range. */
    @Test
    fun `a large id survives the round trip`() {
        val id = 9_007_199_254_740_993L

        assertEquals("home/editAccount/$id", WalletTab.HOME.child(Leaf.EDIT_ACCOUNT, id))
    }

    @Test
    fun `the tab holding a destination is recoverable from its route`() {
        assertEquals(WalletTab.HOME, tabOf(WalletTab.HOME.child(Leaf.MANAGE_ACCOUNTS)))
        assertEquals(WalletTab.SETTINGS, tabOf(WalletTab.SETTINGS.rootRoute))
        assertEquals(WalletTab.TRANSACTIONS, tabOf(WalletTab.TRANSACTIONS.childPattern(Leaf.EDIT_TRANSACTION)))
    }

    @Test
    fun `a route belonging to no tab resolves to nothing rather than guessing`() {
        assertEquals(null, tabOf("somewhere/else"))
        assertEquals(null, tabOf(null))
    }

    @Test
    fun `only tab roots are top level`() {
        WalletTab.entries.forEach { tab ->
            assertTrue(isTopLevel(tab.rootRoute))
            assertTrue(!isTopLevel(tab.child(Leaf.RATES)))
        }
        assertTrue(!isTopLevel(null))
    }

    /**
     * The bar stays on everything inside a tab, not only the tab's own screen. Hiding it on pushed
     * destinations would mean a tab could never be left mid-flow, which is the only way a saved back
     * stack ever comes to exist.
     */
    @Test
    fun `the navigation bar stays on a tab's supporting screens`() {
        WalletTab.entries.forEach { tab ->
            assertTrue(showsNavigationBar(tab.rootRoute))
            assertTrue(showsNavigationBar(tab.child(Leaf.RATES)))
            assertTrue(showsNavigationBar(tab.child(Leaf.MANAGE_ACCOUNTS)))
            assertTrue(showsNavigationBar(tab.child(Leaf.CATEGORIES)))
            assertTrue(showsNavigationBar(tab.child(Leaf.ABOUT)))
        }
    }

    /** A form ends in one committing action; nothing else belongs under its primary button. */
    @Test
    fun `a form gives up the navigation bar`() {
        val tab = WalletTab.HOME

        assertTrue(!showsNavigationBar(tab.child(Leaf.ADD_TRANSACTION)))
        assertTrue(!showsNavigationBar(tab.child(Leaf.ADD_ACCOUNT)))
        assertTrue(!showsNavigationBar(tab.child(Leaf.BASE_CURRENCY)))
        assertTrue(!showsNavigationBar(tab.child(Leaf.EDIT_ACCOUNT, id = 3)))
        assertTrue(!showsNavigationBar(tab.childPattern(Leaf.EDIT_TRANSACTION)))
    }

    @Test
    fun `a route outside the shell has no bar`() {
        assertTrue(!showsNavigationBar(null))
        assertTrue(!showsNavigationBar("somewhere/else"))
    }

    /** Every form is a real leaf, so a rename cannot silently drop one out of the set. */
    @Test
    fun `the form list only names leaves that exist`() {
        val leaves = Leaf.all + Leaf.withId
        Leaf.forms.forEach { form -> assertTrue("$form is not a leaf", form in leaves) }
    }

    @Test
    fun `debts and a debt detail remain on the Home stack with its navigation bar`() {
        val tab = WalletTab.HOME

        assertEquals("home/debts", tab.child(Leaf.DEBTS))
        assertEquals("home/debtDetail/{$ARG_ID}", tab.childPattern(Leaf.DEBT_DETAIL))
        assertTrue(Leaf.DEBTS in Leaf.all)
        assertTrue(Leaf.DEBT_DETAIL in Leaf.withId)
        assertEquals(tab, tabOf(tab.child(Leaf.DEBTS)))
        assertEquals(tab, tabOf(tab.child(Leaf.DEBT_DETAIL, 7)))
        assertTrue(showsNavigationBar(tab.child(Leaf.DEBTS)))
        assertTrue(showsNavigationBar(tab.childPattern(Leaf.DEBT_DETAIL)))
    }

    @Test
    fun `a large debt id reaches detail without truncation`() {
        val id = 4_294_967_296L
        val pattern = WalletTab.HOME.childPattern(Leaf.DEBT_DETAIL)
        val route = WalletTab.HOME.child(Leaf.DEBT_DETAIL, id)

        assertEquals("home/debtDetail/4294967296", route)
        assertEquals(route, pattern.replace("{$ARG_ID}", id.toString()))
        assertTrue(!showsNavigationBar(WalletTab.HOME.child(Leaf.EDIT_TRANSACTION, id)))
    }

    @Test
    fun `repayment form is an id route under Home and gives up the navigation bar`() {
        val id = 4_294_967_296L
        val pattern = WalletTab.HOME.childPattern(Leaf.RECORD_REPAYMENT)
        val route = WalletTab.HOME.child(Leaf.RECORD_REPAYMENT, id)

        assertEquals("home/recordRepayment/{$ARG_ID}", pattern)
        assertEquals("home/recordRepayment/$id", route)
        assertEquals(route, pattern.replace("{$ARG_ID}", id.toString()))
        assertTrue(Leaf.RECORD_REPAYMENT in Leaf.withId)
        assertTrue(Leaf.RECORD_REPAYMENT in Leaf.forms)
        assertTrue(!showsNavigationBar(route))
    }

    @Test
    fun `repayment edit rides the query argument and a new repayment leaves it out`() {
        val pattern = WalletTab.HOME.repaymentPattern()

        assertEquals("home/recordRepayment/{$ARG_ID}?$ARG_REPAYMENT_ID={$ARG_REPAYMENT_ID}", pattern)
        assertEquals("home/recordRepayment/9", WalletTab.HOME.repaymentRoute(9))
        assertEquals("home/recordRepayment/9?repaymentId=4", WalletTab.HOME.repaymentRoute(9, repaymentId = 4))
        assertEquals(
            WalletTab.HOME.repaymentRoute(9, 4),
            pattern.replace("{$ARG_ID}", "9").replace("{$ARG_REPAYMENT_ID}", "4"),
        )
        assertTrue(!showsNavigationBar(WalletTab.HOME.repaymentRoute(9, 4)))
    }

    @Test
    fun `the no-repayment value is not a real row id`() {
        assertEquals(0L, NO_REPAYMENT_ID)
    }
}
