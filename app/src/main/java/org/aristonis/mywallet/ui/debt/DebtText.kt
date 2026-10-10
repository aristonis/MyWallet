package org.aristonis.mywallet.ui.debt

import org.aristonis.mywallet.ui.format.bidiIsolate
import java.time.LocalDate

/** The counterparty as a list-row or history headline, isolated from the numbers beside it. */
internal fun debtRowHeadline(counterparty: String): String = bidiIsolate(counterparty)

/** The history row's supporting line: kind, date and amount, the last two isolated. */
internal fun debtHistorySupporting(kind: String, date: String, amount: String?): String =
    listOfNotNull(kind, bidiIsolate(date), amount?.let(::bidiIsolate)).joinToString(" · ")

/** The day the due-date picker opens on: the chosen due date, else the injected [today]. */
internal fun dueDatePickerStart(dueDate: LocalDate?, today: LocalDate): LocalDate = dueDate ?: today
