package org.aristonis.mywallet.ui.message

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Test

/** A taken debt name reaches the screen as a message carrying the name that clashed. */
class DebtStorageMessagesTest {

    @Test
    fun aTakenDebtNameIsShownWithTheNameThatClashed() {
        assertEquals(UiMessage.DebtNameTaken("Ali"), WalletException.DebtNameTaken("Ali").toUiMessage())
    }

    @Test
    fun theNameIsCarriedAsTypedWithoutChange() {
        assertEquals(UiMessage.DebtNameTaken("\u0639\u0644\u064A"), WalletException.DebtNameTaken("\u0639\u0644\u064A").toUiMessage())
    }
}
