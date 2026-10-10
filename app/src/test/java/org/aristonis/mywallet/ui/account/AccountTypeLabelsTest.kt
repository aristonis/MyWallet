package org.aristonis.mywallet.ui.account

import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Every account type has a label and an opening-balance hint resource. */
class AccountTypeLabelsTest {

    @Test
    fun everyBuiltInType_hasALabelResource() {
        val missing = AccountTypeRegistry.BuiltIns.all.filter { accountTypeLabelRes(it.key) == null }.map { it.key }

        assertEquals("built-in types without a label", emptyList<String>(), missing)
    }

    @Test
    fun builtInLabels_areDistinct() {
        val labels = AccountTypeRegistry.BuiltIns.all.map { accountTypeLabelRes(it.key) }

        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun creditCard_hasItsOwnLabel() {
        assertNotNull(accountTypeLabelRes("credit_card"))
        assertEquals(false, accountTypeLabelRes("credit_card") == accountTypeLabelRes("card"))
    }

    @Test
    fun unknownType_hasNoLabelResource() {
        assertNull(accountTypeLabelRes("no_such_type"))
    }

    @Test
    fun openingBalanceHint_existsOnlyForOwedWhenNegativeTypes() {
        AccountTypeRegistry.BuiltIns.all.forEach { type ->
            val hint = openingBalanceHintRes(type.key)
            if (type.owedWhenNegative) {
                assertNotNull("hint for ${type.key}", hint)
            } else {
                assertNull("no hint for ${type.key}", hint)
            }
        }
    }

    @Test
    fun openingBalanceHint_unknownType_isNull() {
        assertNull(openingBalanceHintRes("no_such_type"))
    }
}
