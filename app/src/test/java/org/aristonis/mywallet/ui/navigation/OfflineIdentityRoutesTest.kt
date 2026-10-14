package org.aristonis.mywallet.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineIdentityRoutesTest {
    @Test
    fun eachOfflineInformationPageHidesTheSettingsTabBar() {
        listOf(Leaf.PRIVACY, Leaf.SUPPORT, Leaf.APP_LICENSE).forEach { leaf ->
            val route = WalletTab.SETTINGS.child(leaf)
            assertTrue(leaf in Leaf.all)
            assertFalse(showsNavigationBar(route))
            assertFalse(leaf in Leaf.forms)
        }
    }
}
