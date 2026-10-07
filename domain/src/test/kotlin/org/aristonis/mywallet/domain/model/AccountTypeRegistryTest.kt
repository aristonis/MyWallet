package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
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
        reg.register(AccountType("wallet"))
        assertEquals("wallet", reg.byKey("wallet")?.key)
        assertEquals(6, reg.all().size)
    }

    /** A key is the whole identity: no display text lives here to be translated or drift. */
    @Test
    fun blankKeyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AccountType("  ") }
    }

    @Test
    fun unknownKey_returnsNull() {
        assertNull(AccountTypeRegistry().byKey("nope"))
    }
}
