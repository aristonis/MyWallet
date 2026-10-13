package org.aristonis.mywallet.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineIdentityRoutesTest {
    @Test
    fun eachOfflineInformationPageKeepsTheSettingsTabBar() {
        listOf(Leaf.PRIVACY, Leaf.SUPPORT).forEach { leaf ->
            val route = WalletTab.SETTINGS.child(leaf)
            assertTrue(leaf in Leaf.all)
            assertTrue(showsNavigationBar(route))
            assertFalse(leaf in Leaf.forms)
        }
    }
}
