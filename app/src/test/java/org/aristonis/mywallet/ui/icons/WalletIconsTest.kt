package org.aristonis.mywallet.ui.icons

import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WalletIconsTest {

    @Test
    fun `cash, card and bank each get their own icon`() {
        val cash = accountTypeIcon(AccountTypeRegistry.BuiltIns.CASH.key)
        val card = accountTypeIcon(AccountTypeRegistry.BuiltIns.CARD.key)
        val bank = accountTypeIcon(AccountTypeRegistry.BuiltIns.BANK.key)

        assertNotEquals(cash, card)
        assertNotEquals(card, bank)
        assertNotEquals(cash, bank)
    }

    @Test
    fun `a type with no icon of its own falls back to the wallet`() {
        assertEquals(WalletIcons.AccountDefault, accountTypeIcon(AccountTypeRegistry.BuiltIns.OTHER.key))
    }

    /**
     * A type registered at runtime must not crash or blank the row. Adding an icon for it is a map
     * insert here, never an edit to a shared branch.
     */
    @Test
    fun `an unregistered type still renders the fallback icon`() {
        assertEquals(WalletIcons.AccountDefault, accountTypeIcon("crypto-wallet"))
    }

    @Test
    fun `every built-in account type resolves to an icon`() {
        AccountTypeRegistry.BuiltIns.all.forEach { type ->
            assertEquals(true, accountTypeIcon(type.key).name.isNotBlank())
        }
    }
}
