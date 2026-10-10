package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide

/**
 * Pure debt row mappers (no Room runtime), unit-tested on the JVM. The row keeps the trimmed name
 * and its key side by side; the key is what the unique index and every lookup compare.
 */

/** What [WalletException.UnreadableRow] names as the kind of a debt row it could not read. */
private const val DEBT_ROW_KIND = "DEBT"

/**
 * The stored debt as the domain sees it.
 *
 * The key is recomputed from the stored name and must equal the stored one. A row whose key does
 * not match was written by something other than this app (a hand-edited database, or a folding
 * that has since changed), and lookups by key would miss it while the unique index let a second row
 * for the same person in, so it is refused rather than read.
 *
 * @throws WalletException.UnreadableRow naming the row when the name is blank, the side or currency
 *   is not one a debt can have, or the stored key differs from the recomputed one.
 */
fun DebtEntity.toDomain(): Debt {
    val debt = try {
        Debt(
            id = id,
            counterparty = counterparty,
            side = DebtSide.valueOf(side),
            currencyCode = currencyCode,
            dueDate = dueDate,
            note = note,
        )
    } catch (e: WalletException.NameBlank) {
        throw WalletException.UnreadableRow(id, DEBT_ROW_KIND, e)
    } catch (e: IllegalArgumentException) {
        // An unknown side name, or a blank currency code.
        throw WalletException.UnreadableRow(id, DEBT_ROW_KIND, e)
    }
    if (debt.key.value != counterpartyKey) {
        throw WalletException.UnreadableRow(
            id,
            DEBT_ROW_KIND,
            IllegalArgumentException("debt $id stores a key that is not the key of its name"),
        )
    }
    return debt
}

fun Debt.toEntity(): DebtEntity = DebtEntity(
    id = id,
    counterparty = counterparty,
    counterpartyKey = key.value,
    side = side.name,
    currencyCode = currencyCode,
    dueDate = dueDate,
    note = note,
)
