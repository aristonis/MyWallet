package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.ledger.recipe
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.MissingSelectionException
import org.aristonis.mywallet.ui.message.UiMessage
import java.math.BigDecimal
import java.time.LocalDate

/** What the user has chosen and typed on an entry form, whichever screen it is on. */
data class EntryFormInputs(
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val subCategoryId: Long? = null,
    val destAccountId: Long? = null,
    val amountInput: String = "",
    val counterparty: String = "",
    val currencyCode: String? = null,
    val debtSide: DebtSide = DebtSide.I_OWE,
    val debtId: Long? = null,
)

/** The entry the form built, with the debt it belongs to (a name, side and currency) when it has one. */
data class BuiltEntry(val entry: Transaction, val debt: DebtRef? = null)

/**
 * How the form treats one entry type. The add and the edit form both go through it, so what a type
 * offers, what it needs and the entry it builds can never differ between the two screens.
 */
interface EntryFormRule {
    val type: TransactionType
    val kind: String
    val requiresAccount: Boolean get() = true
    val requiresCategory: Boolean get() = false
    val requiresCounterparty: Boolean get() = false
    val requiresCurrency: Boolean get() = false
    val showsDebtSide: Boolean get() = false
    val countsInTracking: Boolean get() = false

    fun currency(inputs: EntryFormInputs, accounts: List<Account>): String? =
        if (requiresAccount) accounts.firstOrNull { it.id == inputs.accountId }?.currencyCode else inputs.currencyCode

    fun quoteLegs(inputs: EntryFormInputs, accounts: List<Account>, loaded: Transaction? = null): List<QuoteLeg> =
        currency(inputs, accounts)?.let { listOf(QuoteLeg(it, inputs.amountInput, countsInTracking)) }.orEmpty()

    fun quotePair(inputs: EntryFormInputs, accounts: List<Account>, loaded: Transaction? = null): QuotePair? = null
    fun keptRates(entry: Transaction): Map<String, BigDecimal?> = emptyMap()

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
    ): BuiltEntry

    /**
     * The inputs that show [entry] on the form.
     *
     * @throws IllegalArgumentException when [entry] is not of this rule's type.
     */
    fun load(entry: Transaction, moneyParser: MoneyParser): EntryFormInputs
}

/** Every rule, by entry type or by stored entry. */
object EntryFormRules {

    val all: List<EntryFormRule> = listOf(IncomeFormRule, ExpenseFormRule, TransferFormRule, RefundFormRule,
        BorrowFormRule, LendFormRule, OwedBeforeFormRule, CreditPurchaseFormRule, EarnedUnpaidFormRule)
    private val byType = all.associateBy { it.type }
    private val byKind = all.associateBy { it.kind }
    /** The rule for [type]; every type the form records has one. */
    fun forType(type: TransactionType): EntryFormRule = checkNotNull(byType[type])
    /** The rule that edits [entry], or null for a kind the form cannot edit (repayments and write-offs). */
    fun forEntry(entry: Transaction): EntryFormRule? = byKind[entry.recipe().kind]
}

/** A note typed as blanks is no note at all. */
internal fun noteOrNull(note: String): String? = note.trim().ifEmpty { null }

/** The chosen account, or a refusal that names it as missing. */
internal fun requireAccount(inputs: EntryFormInputs, accounts: List<Account>): Account =
    accounts.firstOrNull { it.id == inputs.accountId && !it.archived }
        ?: throw MissingSelectionException(UiMessage.AccountRequired)

/** Refuses an entry of another type than [rule]'s: a bug in the caller, never something the user typed. */
internal fun wrongType(rule: EntryFormRule, loaded: Transaction): Nothing =
    throw IllegalArgumentException("the ${rule.type} form cannot hold a ${loaded::class.simpleName}")
