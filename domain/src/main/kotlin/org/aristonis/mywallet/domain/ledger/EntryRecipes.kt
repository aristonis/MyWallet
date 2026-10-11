package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.ledger.recipe.BorrowRecipe
import org.aristonis.mywallet.domain.ledger.recipe.CreditPurchaseRecipe
import org.aristonis.mywallet.domain.ledger.recipe.EarnedUnpaidRecipe
import org.aristonis.mywallet.domain.ledger.recipe.ExpenseRecipe
import org.aristonis.mywallet.domain.ledger.recipe.IncomeRecipe
import org.aristonis.mywallet.domain.ledger.recipe.LendRecipe
import org.aristonis.mywallet.domain.ledger.recipe.OwedBeforeRecipe
import org.aristonis.mywallet.domain.ledger.recipe.RefundRecipe
import org.aristonis.mywallet.domain.ledger.recipe.RepaymentRecipe
import org.aristonis.mywallet.domain.ledger.recipe.TransferRecipe
import org.aristonis.mywallet.domain.ledger.recipe.WriteOffRecipe
import org.aristonis.mywallet.domain.model.Transaction

/**
 * Every recipe, looked up by the kind stored on a header. A kind missing here reads as unknown,
 * which is reported to the owner instead of being summed or skipped.
 */
object EntryRecipes {

    /** In a fixed order so anything listed from it stays stable. */
    val all: List<EntryRecipe<out Transaction>> = listOf(
        IncomeRecipe,
        ExpenseRecipe,
        RefundRecipe,
        TransferRecipe,
        BorrowRecipe,
        LendRecipe,
        OwedBeforeRecipe,
        CreditPurchaseRecipe,
        EarnedUnpaidRecipe,
        RepaymentRecipe,
        WriteOffRecipe,
    )

    private val byKind: Map<String, EntryRecipe<out Transaction>> = all.associateBy { it.kind }

    /** The recipe for a stored [kind], or null when no recipe reads it. */
    fun forKind(kind: String): EntryRecipe<out Transaction>? = byKind[kind]

    /**
     * How a category leg in [role] on an entry of stored [kind] counts in Tracking, or null when no
     * recipe reads the kind or the recipe has no rule for the role.
     */
    fun trackingRule(kind: String, role: LegRole): TrackingRule? = forKind(kind)?.trackingRuleByRole?.get(role)
}

/**
 * The recipe for this entry's kind. One exhaustive `when` with no `else`: adding a [Transaction]
 * kind fails to compile here until it has a recipe.
 *
 * @throws IllegalStateException when the recipe is missing from [EntryRecipes.all]. Such an entry
 *   would save but never read back, so the save fails before anything is written.
 */
fun Transaction.recipe(): EntryRecipe<Transaction> {
    val recipe: EntryRecipe<out Transaction> = when (this) {
        is Transaction.Income -> IncomeRecipe
        is Transaction.Expense -> ExpenseRecipe
        is Transaction.Refund -> RefundRecipe
        is Transaction.Transfer -> TransferRecipe
        is Transaction.Borrow -> BorrowRecipe
        is Transaction.Lend -> LendRecipe
        is Transaction.OwedBefore -> OwedBeforeRecipe
        is Transaction.CreditPurchase -> CreditPurchaseRecipe
        is Transaction.EarnedUnpaid -> EarnedUnpaidRecipe
        is Transaction.Repayment -> RepaymentRecipe
        is Transaction.WriteOff -> WriteOffRecipe
    }
    check(EntryRecipes.forKind(recipe.kind) === recipe) {
        "the ${recipe.kind} recipe is not registered in EntryRecipes.all, so its entries could not be read back"
    }
    // Safe: each branch above returns the recipe for exactly the kind it matched.
    @Suppress("UNCHECKED_CAST")
    return recipe as EntryRecipe<Transaction>
}

/** This entry as stored header and legs. */
fun Transaction.toParts(): EntryParts = recipe().toLegs(this)
