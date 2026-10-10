package org.aristonis.mywallet.ui.message

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Test

class DebtUiMessageMappingTest {
    @Test fun aMissingDebtHasANamedMessageWithoutItsInternalId() {
        assertEquals(UiMessage.DebtNotFound, WalletException.DebtNotFound(7).toUiMessage())
        assertEquals(WalletException.DebtNotFound(7).toUiMessage(), WalletException.DebtNotFound(99).toUiMessage())
    }

    @Test fun aDebtSideMismatchHasANamedMessageWithoutItsInternalId() {
        assertEquals(UiMessage.DebtSideMismatch, WalletException.DebtSideMismatch(7).toUiMessage())
        assertEquals(WalletException.DebtSideMismatch(7).toUiMessage(), WalletException.DebtSideMismatch(99).toUiMessage())
    }
}
