package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AccountTypeRegistryTest {

    @Test
    fun seedsBuiltIns() {
        val reg = AccountTypeRegistry()
        assertEquals(5, reg.all().size)
        assertNotNull(reg.byKey("cash"))
    }

    @Test
    fun registerAddsNewType_ocp() {
        val reg = AccountTypeRegistry()
        reg.register(AccountType("wallet", "E-Wallet"))
        assertEquals("E-Wallet", reg.byKey("wallet")?.displayName)
        assertEquals(6, reg.all().size)
    }

    @Test
    fun unknownKey_returnsNull() {
        assertNull(AccountTypeRegistry().byKey("nope"))
    }
}
