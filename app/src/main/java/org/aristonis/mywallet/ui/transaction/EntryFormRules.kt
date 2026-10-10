package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.MissingSelectionException
import org.aristonis.mywallet.ui.message.UiMessage
import java.time.LocalDate

/** What the user has chosen and typed on an entry form, whichever screen it is on. */
data class EntryFormInputs(
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val subCategoryId: Long? = null,
    val destAccountId: Long? = null,
    val amountInput: String = "",
)

/**
 * How the form treats one entry type. The add and the edit form both go through it, so what a type
 * offers, what it needs and the entry it builds can never differ between the two screens.
 */
interface EntryFormRule {
    val type: TransactionType

    /** The categories the picker lists for this type, top-level only; sub-categories hang off them. */
    fun categoriesOffered(all: List<Category>): List<Category>

    /**
     * Whether the content of the form is enough to save. The screens add their own checks on top
     * (a save running, a rate still missing).
     */
    fun isSubmittable(inputs: EntryFormInputs): Boolean

    /**
     * The entry the form describes. With [loaded] null this is a new entry (id 0); with it set this is
     * an edit of [loaded], which keeps its id and every field the form never shows. The amount is built
     * in the chosen account's currency, so it matches the account by construction.
     *
     * @throws MissingSelectionException naming the missing account, category or destination.
     * @throws IllegalArgumentException when [loaded] is not of this rule's type.
     */
    fun build(
        inputs: EntryFormInputs,
        accounts: List<Account>,
        moneyParser: MoneyParser,
        date: LocalDate,
        note: String,
        loaded: Transaction? = null,
    ): Transaction

    /**
     * The inputs that show [entry] on the form.
     *
     * @throws IllegalArgumentException when [entry] is not of this rule's type.
     */
    fun load(entry: Transaction, moneyParser: MoneyParser): EntryFormInputs
}

/** Every rule, by entry type or by stored entry. */
object EntryFormRules {

    /** The rule for [type]; every type the form records has one. */
    fun forType(type: TransactionType): EntryFormRule = when (type) {
        TransactionType.INCOME -> IncomeFormRule
        TransactionType.EXPENSE -> ExpenseFormRule
        TransactionType.TRANSFER -> TransferFormRule
    }

    /** The rule that edits [entry], or null for a kind the form cannot edit yet (the debt kinds). */
    fun forEntry(entry: Transaction): EntryFormRule? = when (entry) {
        is Transaction.Income -> IncomeFormRule
        is Transaction.Expense -> ExpenseFormRule
        is Transaction.Transfer -> TransferFormRule
        is Transaction.Borrow,
        is Transaction.Lend,
        is Transaction.OwedBefore,
        is Transaction.CreditPurchase,
        is Transaction.EarnedUnpaid,
        is Transaction.Repayment,
        is Transaction.WriteOff,
        -> null
    }
}

/** A note typed as blanks is no note at all. */
internal fun noteOrNull(note: String): String? = note.trim().ifEmpty { null }

/** The chosen account, or a refusal that names it as missing. */
internal fun requireAccount(inputs: EntryFormInputs, accounts: List<Account>): Account =
    accounts.firstOrNull { it.id == inputs.accountId } ?: throw MissingSelectionException(UiMessage.AccountRequired)

/** Refuses an entry of another type than [rule]'s: a bug in the caller, never something the user typed. */
internal fun wrongType(rule: EntryFormRule, loaded: Transaction): Nothing =
    throw IllegalArgumentException("the ${rule.type} form cannot hold a ${loaded::class.simpleName}")
