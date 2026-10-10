package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.MissingSelectionException
import org.aristonis.mywallet.ui.message.UiMessage
import java.math.BigDecimal
import java.time.LocalDate

/**
 * An income or an expense: one account, one category of [categoryKind], an amount. The two differ only
 * in the kind of category and the entry they build.
 */
internal abstract class CategoryFormRule(
    final override val type: TransactionType,
    private val categoryKind: CategoryKind,
) : EntryFormRule {

    final override fun categoriesOffered(all: List<Category>): List<Category> =
        all.filter { it.kind == categoryKind && !it.isSubCategory }

    final override fun isSubmittable(inputs: EntryFormInputs): Boolean =
        inputs.amountInput.isNotBlank() && inputs.accountId != null && inputs.categoryId != null

    final override fun build(
        inputs: EntryFormInputs,
        accounts: List<Account>,
        moneyParser: MoneyParser,
        date: LocalDate,
        note: String,
        loaded: Transaction?,
    ): Transaction {
        // Account, then amount, then category: the order the screens always reported a refusal in.
        val account = requireAccount(inputs, accounts)
        val fields = CategoryEntryFields(
            accountId = account.id,
            amount = moneyParser.parseAmount(inputs.amountInput, account.currencyCode),
            categoryId = inputs.categoryId ?: throw MissingSelectionException(UiMessage.CategoryRequired),
            subCategoryId = inputs.subCategoryId,
            date = date,
            note = noteOrNull(note),
        )
        return if (loaded == null) newEntry(fields) else edited(loaded, fields)
    }

    /** A new entry of this type from [fields], with no stored rate yet: the save decides it. */
    protected abstract fun newEntry(fields: CategoryEntryFields): Transaction

    /** [loaded] with [fields] applied; its id and stored rate are kept. */
    protected abstract fun edited(loaded: Transaction, fields: CategoryEntryFields): Transaction

    protected fun inputsOf(
        accountId: Long,
        categoryId: Long,
        subCategoryId: Long?,
        amount: Money,
        moneyParser: MoneyParser,
    ) = EntryFormInputs(
        accountId = accountId,
        categoryId = categoryId,
        subCategoryId = subCategoryId,
        amountInput = moneyParser.toInputString(amount.amount),
    )
}

/** What the form decides on an income or an expense. */
internal data class CategoryEntryFields(
    val accountId: Long,
    val amount: Money,
    val categoryId: Long,
    val subCategoryId: Long?,
    val date: LocalDate,
    val note: String?,
)

internal object IncomeFormRule : CategoryFormRule(TransactionType.INCOME, CategoryKind.INCOME) {

    override fun newEntry(fields: CategoryEntryFields): Transaction = Transaction.Income(
        accountId = fields.accountId, amount = fields.amount, categoryId = fields.categoryId,
        subCategoryId = fields.subCategoryId, date = fields.date, note = fields.note,
    )

    override fun edited(loaded: Transaction, fields: CategoryEntryFields): Transaction {
        if (loaded !is Transaction.Income) wrongType(this, loaded)
        return loaded.copy(
            accountId = fields.accountId, amount = fields.amount, categoryId = fields.categoryId,
            subCategoryId = fields.subCategoryId, date = fields.date, note = fields.note,
        )
    }

    override fun load(entry: Transaction, moneyParser: MoneyParser): EntryFormInputs {
        if (entry !is Transaction.Income) wrongType(this, entry)
        return inputsOf(entry.accountId, entry.categoryId, entry.subCategoryId, entry.amount, moneyParser)
    }
}

internal object ExpenseFormRule : CategoryFormRule(TransactionType.EXPENSE, CategoryKind.EXPENSE) {

    override fun newEntry(fields: CategoryEntryFields): Transaction = Transaction.Expense(
        accountId = fields.accountId, amount = fields.amount, categoryId = fields.categoryId,
        subCategoryId = fields.subCategoryId, date = fields.date, note = fields.note,
    )

    override fun edited(loaded: Transaction, fields: CategoryEntryFields): Transaction {
        if (loaded !is Transaction.Expense) wrongType(this, loaded)
        return loaded.copy(
            accountId = fields.accountId, amount = fields.amount, categoryId = fields.categoryId,
            subCategoryId = fields.subCategoryId, date = fields.date, note = fields.note,
        )
    }

    override fun load(entry: Transaction, moneyParser: MoneyParser): EntryFormInputs {
        if (entry !is Transaction.Expense) wrongType(this, entry)
        return inputsOf(entry.accountId, entry.categoryId, entry.subCategoryId, entry.amount, moneyParser)
    }
}

/**
 * A transfer: two different accounts and an amount, no category. A new transfer's destination amount
 * and rate are placeholders the save prices from the source amount and the typed rates. An edit keeps
 * the pair, the destination amount and the rate: the rate is bound to that currency pair, so only the
 * source amount, the date and the note move (the save re-derives the destination leg).
 */
internal object TransferFormRule : EntryFormRule {

    override val type: TransactionType = TransactionType.TRANSFER

    override fun categoriesOffered(all: List<Category>): List<Category> = emptyList()

    override fun isSubmittable(inputs: EntryFormInputs): Boolean =
        inputs.amountInput.isNotBlank() && inputs.accountId != null && inputs.destAccountId != null &&
            inputs.destAccountId != inputs.accountId

    override fun build(
        inputs: EntryFormInputs,
        accounts: List<Account>,
        moneyParser: MoneyParser,
        date: LocalDate,
        note: String,
        loaded: Transaction?,
    ): Transaction {
        if (loaded != null) {
            if (loaded !is Transaction.Transfer) wrongType(this, loaded)
            // Currency comes from the locked source leg, so the domain's pair guard always passes.
            val sourceAmount = moneyParser.parseAmount(inputs.amountInput, loaded.sourceAmount.currencyCode)
            return loaded.copy(sourceAmount = sourceAmount, date = date, note = noteOrNull(note))
        }
        val source = requireAccount(inputs, accounts)
        val amount = moneyParser.parseAmount(inputs.amountInput, source.currencyCode)
        val dest = accounts.firstOrNull { it.id == inputs.destAccountId }
            ?: throw MissingSelectionException(UiMessage.DestinationAccountRequired)
        return Transaction.Transfer(
            sourceAccountId = source.id,
            destAccountId = dest.id,
            sourceAmount = amount,
            destAmount = Money.of(amount.amount, dest.currencyCode),
            rateUsed = BigDecimal.ONE,
            date = date,
            note = noteOrNull(note),
        )
    }

    override fun load(entry: Transaction, moneyParser: MoneyParser): EntryFormInputs {
        if (entry !is Transaction.Transfer) wrongType(this, entry)
        return EntryFormInputs(
            accountId = entry.sourceAccountId,
            destAccountId = entry.destAccountId,
            amountInput = moneyParser.toInputString(entry.sourceAmount.amount),
        )
    }
}
