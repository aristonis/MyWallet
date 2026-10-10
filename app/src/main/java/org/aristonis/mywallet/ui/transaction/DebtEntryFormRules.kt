package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.MissingSelectionException
import org.aristonis.mywallet.ui.message.UiMessage
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The shared shape of the five debt kinds: a counterparty, a currency (from the account when the kind has
 * one), an optional category, and the side of the debt, fixed by the kind or chosen on the form.
 */
internal abstract class DebtFormRule(
    final override val type: TransactionType,
    final override val requiresAccount: Boolean,
    private val fixedSide: DebtSide?,
    private val categoryKind: CategoryKind? = null,
) : EntryFormRule {
    final override val kind = type.name
    final override val requiresCounterparty = true
    final override val requiresCurrency = !requiresAccount
    final override val requiresCategory = categoryKind != null
    final override val countsInTracking = requiresCategory
    final override val showsDebtSide = fixedSide == null

    final override fun categoriesOffered(all: List<Category>): List<Category> =
        all.filter { categoryKind != null && it.kind == categoryKind && !it.isSubCategory }

    final override fun isSubmittable(inputs: EntryFormInputs): Boolean =
        inputs.amountInput.isNotBlank() && inputs.counterparty.isNotBlank() &&
            (!requiresAccount || inputs.accountId != null) &&
            (!requiresCurrency || !inputs.currencyCode.isNullOrBlank()) &&
            (!requiresCategory || inputs.categoryId != null)

    final override fun build(inputs: EntryFormInputs, accounts: List<Account>, moneyParser: MoneyParser,
        date: LocalDate, note: String, loaded: Transaction?): BuiltEntry {
        val account = if (requiresAccount) requireAccount(inputs, accounts) else null
        if (inputs.counterparty.isBlank()) throw MissingSelectionException(UiMessage.NameRequired)
        val currency = account?.currencyCode ?: inputs.currencyCode?.takeIf { it.isNotBlank() }
            ?: throw MissingSelectionException(UiMessage.CurrencyRequired)
        val side = fixedSide ?: inputs.debtSide
        val fields = DebtEntryFields(id = loaded?.id ?: 0, debtId = inputs.debtId ?: 0,
            accountId = account?.id, side = side, amount = moneyParser.parseAmount(inputs.amountInput, currency),
            categoryId = if (requiresCategory) {
                inputs.categoryId ?: throw MissingSelectionException(UiMessage.CategoryRequired)
            } else {
                null
            },
            subCategoryId = inputs.subCategoryId, date = date, note = noteOrNull(note))
        return BuiltEntry(buildEntry(fields, loaded), DebtRef(inputs.counterparty, side, currency))
    }

    /** The entry of this kind built from [fields]; [loaded] is the entry being edited, or null for a new one. */
    protected abstract fun buildEntry(fields: DebtEntryFields, loaded: Transaction?): Transaction

    /** The form inputs that show a stored debt entry. */
    protected fun inputs(debtId: Long, side: DebtSide, amount: Money, parser: MoneyParser,
        accountId: Long? = null, categoryId: Long? = null, subCategoryId: Long? = null) = EntryFormInputs(
        accountId = accountId, categoryId = categoryId, subCategoryId = subCategoryId,
        debtId = debtId, debtSide = side, currencyCode = amount.currencyCode,
        amountInput = parser.toInputString(amount.amount))
}

/** What the form decides on a debt entry before the kind builds it. */
internal data class DebtEntryFields(val id: Long, val debtId: Long, val accountId: Long?, val side: DebtSide,
    val amount: Money, val categoryId: Long?, val subCategoryId: Long?, val date: LocalDate, val note: String?)

/** Money borrowed into an account. */
internal object BorrowFormRule : DebtFormRule(TransactionType.BORROW, true, DebtSide.I_OWE) {
    override fun buildEntry(fields: DebtEntryFields, loaded: Transaction?): Transaction {
        if (loaded != null && loaded !is Transaction.Borrow) wrongType(this, loaded)
        return Transaction.Borrow(
            fields.id, checkNotNull(fields.accountId), fields.debtId, fields.amount, fields.date, fields.note,
        )
    }

    override fun load(entry: Transaction, moneyParser: MoneyParser): EntryFormInputs {
        if (entry !is Transaction.Borrow) wrongType(this, entry)
        return inputs(entry.debtId, DebtSide.I_OWE, entry.amount, moneyParser, accountId = entry.accountId)
    }
}

/** Money lent out of an account. */
internal object LendFormRule : DebtFormRule(TransactionType.LEND, true, DebtSide.OWED_TO_ME) {
    override fun buildEntry(fields: DebtEntryFields, loaded: Transaction?): Transaction {
        if (loaded != null && loaded !is Transaction.Lend) wrongType(this, loaded)
        return Transaction.Lend(
            fields.id, checkNotNull(fields.accountId), fields.debtId, fields.amount, fields.date, fields.note,
        )
    }

    override fun load(entry: Transaction, moneyParser: MoneyParser): EntryFormInputs {
        if (entry !is Transaction.Lend) wrongType(this, entry)
        return inputs(entry.debtId, DebtSide.OWED_TO_ME, entry.amount, moneyParser, accountId = entry.accountId)
    }
}

/** A debt that existed before the wallet; no account moves. */
internal object OwedBeforeFormRule : DebtFormRule(TransactionType.OWED_BEFORE, false, null) {
    override fun buildEntry(fields: DebtEntryFields, loaded: Transaction?): Transaction {
        if (loaded != null && loaded !is Transaction.OwedBefore) wrongType(this, loaded)
        return Transaction.OwedBefore(fields.id, fields.debtId, fields.side, fields.amount, fields.date, fields.note)
    }

    override fun load(entry: Transaction, moneyParser: MoneyParser): EntryFormInputs {
        if (entry !is Transaction.OwedBefore) wrongType(this, entry)
        return inputs(entry.debtId, entry.side, entry.amount, moneyParser)
    }
}

/** A purchase paid for by owing; counts as an expense. */
internal object CreditPurchaseFormRule :
    DebtFormRule(TransactionType.CREDIT_PURCHASE, false, DebtSide.I_OWE, CategoryKind.EXPENSE) {
    override fun buildEntry(fields: DebtEntryFields, loaded: Transaction?): Transaction {
        if (loaded != null && loaded !is Transaction.CreditPurchase) wrongType(this, loaded)
        return Transaction.CreditPurchase(fields.id, fields.debtId, fields.amount, checkNotNull(fields.categoryId),
            fields.subCategoryId, fields.date, fields.note, (loaded as? Transaction.CreditPurchase)?.rateToBase)
    }

    override fun load(entry: Transaction, moneyParser: MoneyParser): EntryFormInputs {
        if (entry !is Transaction.CreditPurchase) wrongType(this, entry)
        return inputs(
            entry.debtId, DebtSide.I_OWE, entry.amount, moneyParser,
            categoryId = entry.categoryId, subCategoryId = entry.subCategoryId,
        )
    }

    override fun keptRates(entry: Transaction): Map<String, BigDecimal?> {
        if (entry !is Transaction.CreditPurchase) wrongType(this, entry)
        return mapOf(entry.amount.currencyCode to entry.rateToBase)
    }
}

/** Income earned but not yet paid; counts as income. */
internal object EarnedUnpaidFormRule :
    DebtFormRule(TransactionType.EARNED_UNPAID, false, DebtSide.OWED_TO_ME, CategoryKind.INCOME) {
    override fun buildEntry(fields: DebtEntryFields, loaded: Transaction?): Transaction {
        if (loaded != null && loaded !is Transaction.EarnedUnpaid) wrongType(this, loaded)
        return Transaction.EarnedUnpaid(fields.id, fields.debtId, fields.amount, checkNotNull(fields.categoryId),
            fields.subCategoryId, fields.date, fields.note, (loaded as? Transaction.EarnedUnpaid)?.rateToBase)
    }

    override fun load(entry: Transaction, moneyParser: MoneyParser): EntryFormInputs {
        if (entry !is Transaction.EarnedUnpaid) wrongType(this, entry)
        return inputs(
            entry.debtId, DebtSide.OWED_TO_ME, entry.amount, moneyParser,
            categoryId = entry.categoryId, subCategoryId = entry.subCategoryId,
        )
    }

    override fun keptRates(entry: Transaction): Map<String, BigDecimal?> {
        if (entry !is Transaction.EarnedUnpaid) wrongType(this, entry)
        return mapOf(entry.amount.currencyCode to entry.rateToBase)
    }
}
