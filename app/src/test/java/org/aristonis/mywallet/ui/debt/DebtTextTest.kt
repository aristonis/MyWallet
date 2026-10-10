package org.aristonis.mywallet.ui.debt

import org.aristonis.mywallet.ui.format.bidiIsolate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DebtTextTest {
    @Test fun theCounterpartyHeadlineIsBidiIsolated() {
        assertEquals(bidiIsolate("سارة"), debtRowHeadline("سارة"))
    }

    @Test fun historySupportingTextIsolatesTheDateAndTheAmount() {
        assertEquals(
            "Borrowed · " + bidiIsolate("10 Oct 2026") + " · " + bidiIsolate("-3.00 EUR"),
            debtHistorySupporting("Borrowed", "10 Oct 2026", "-3.00 EUR"))
    }

    @Test fun historySupportingTextDropsAMissingAmount() {
        assertEquals("Borrowed · " + bidiIsolate("10 Oct 2026"), debtHistorySupporting("Borrowed", "10 Oct 2026", null))
    }

    @Test fun theDueDatePickerOpensOnTheInjectedTodayWhenNoDueDateIsSet() {
        val today = LocalDate.of(2020, 1, 2)
        assertEquals(today, dueDatePickerStart(null, today))
    }

    @Test fun theDueDatePickerOpensOnTheChosenDueDate() {
        assertEquals(LocalDate.of(2026, 12, 1), dueDatePickerStart(LocalDate.of(2026, 12, 1), LocalDate.of(2020, 1, 2)))
    }
}
