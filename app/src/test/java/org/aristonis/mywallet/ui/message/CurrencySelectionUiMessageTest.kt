package org.aristonis.mywallet.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencySelectionUiMessageTest {
    @Test
    fun anUnselectedCurrencyKeepsItsSpecificFormRefusal() {
        val failure: Throwable = MissingSelectionException(UiMessage.CurrencyRequired)

        assertEquals(UiMessage.CurrencyRequired, failure.toUiMessage())
    }
}
