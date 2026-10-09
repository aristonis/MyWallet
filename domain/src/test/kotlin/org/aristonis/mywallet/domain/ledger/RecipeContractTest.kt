package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The rules every kind of entry must keep, checked over every registered recipe and every example it
 * offers. A new kind only has to register a recipe with examples to be held to all of them, which is
 * how a kind that forgets to say how it counts in Tracking fails here instead of vanishing from the
 * totals.
 */
class RecipeContractTest {

    @Test
    fun everyRegisteredRecipeKeepsTheContract() {
        EntryRecipes.all.forEach { recipe ->
            assertEquals(recipe.kind, emptyList<String>(), violations(recipe))
        }
    }

    @Test
    fun theRegistryHoldsTheThreeExistingKinds() {
        assertEquals(setOf("INCOME", "EXPENSE", "TRANSFER"), EntryRecipes.all.map { it.kind }.toSet())
        EntryRecipes.all.forEach { assertEquals(it, EntryRecipes.forKind(it.kind)) }
        assertEquals(null, EntryRecipes.forKind("NOT_A_KIND"))
    }

    @Test
    fun everyExampleFindsItsOwnRecipe() {
        EntryRecipes.all.forEach { recipe ->
            recipe.examples().forEach { example -> assertEquals(recipe.kind, example.recipe().kind) }
        }
    }

    @Test
    fun aRecipeWhoseCategoryLegHasNoRuleFailsTheContract() {
        val broken = object : EntryRecipe<Transaction.Expense> by expenseRecipe() {
            override fun trackingRules(entry: Transaction.Expense): Map<LegRole, TrackingRule> = emptyMap()
        }
        assertTrue(violations(broken).any { "no tracking rule" in it })
    }

    @Test
    fun aMissingLegMakesTheEntryUnreadable() {
        EntryRecipes.all.forEach { recipe ->
            recipe.examples().forEach { example ->
                val parts = recipe.asAny().toLegs(example)
                val short = parts.copy(legs = parts.legs.drop(1))
                val error = assertThrows(WalletException.UnreadableRow::class.java) { recipe.asAny().fromLegs(short) }
                assertEquals(parts.header.id, error.id)
                assertEquals(recipe.kind, error.kind)
            }
        }
    }

    @Test
    fun legsThatDoNotAddUpMakeTheEntryUnreadable() {
        val recipe = expenseRecipe()
        val parts = recipe.toLegs(recipe.examples().first())
        val tampered = parts.copy(
            legs = parts.legs.map { if (it.role == LegRole.MAIN) it.copy(amount = it.amount + Money.of("0.01", it.amount.currencyCode)) else it },
        )
        assertThrows(WalletException.UnreadableRow::class.java) { recipe.fromLegs(tampered) }
    }

    @Test
    fun everyDamagedShapeOfAnExampleIsUnreadableAndNamesItsRow() {
        EntryRecipes.all.forEach { recipe ->
            recipe.examples().forEach { example ->
                val parts = recipe.asAny().toLegs(example)
                damagedCopies(parts).forEach { (damage, broken) ->
                    val error = assertThrows("${recipe.kind}: $damage", WalletException.UnreadableRow::class.java) {
                        recipe.asAny().fromLegs(broken)
                    }
                    assertEquals("${recipe.kind}: $damage", parts.header.id, error.id)
                }
            }
        }
    }

    @Test
    fun aCategoryLegThatDoesNotCountFailsTheContract() {
        val broken = object : EntryRecipe<Transaction.Expense> by expenseRecipe() {
            override fun trackingRules(entry: Transaction.Expense) = mapOf(LegRole.MAIN to TrackingRule.NOT_COUNTED)
        }
        assertTrue(violations(broken).any { "category leg that does not count" in it })
    }

    @Test
    fun aRuleNeverChangesTheSignOfALeg() {
        val negativeExpense = object : EntryRecipe<Transaction.Expense> by expenseRecipe() {
            override fun toLegs(entry: Transaction.Expense): EntryParts = expenseRecipe().toLegs(entry).let { parts ->
                parts.copy(legs = parts.legs.map { if (it.role == LegRole.MAIN) it.copy(amount = -it.amount) else it })
            }
        }
        assertTrue(violations(negativeExpense).any { "negative EXPENSE leg" in it })
        val positiveRefund = object : EntryRecipe<Transaction.Expense> by expenseRecipe() {
            override fun trackingRules(entry: Transaction.Expense) = mapOf(LegRole.MAIN to TrackingRule.REDUCES_EXPENSE)
        }
        assertTrue(violations(positiveRefund).any { "REDUCES_EXPENSE leg that is not negative" in it })
    }

    /**
     * Tracking breaks a tie between the category legs of one entry without comparing currencies, so
     * every example must keep all its category legs in a single currency.
     */
    @Test
    fun everyExamplesCategoryLegsShareOneCurrency() {
        EntryRecipes.all.forEach { recipe ->
            recipe.asAny().examples().forEach { example ->
                val currencies = recipe.asAny().toLegs(example).legs
                    .filter { it.target is LegTarget.Category }
                    .map { it.amount.currencyCode }
                    .toSet()
                assertTrue("${recipe.kind}: category legs in $currencies", currencies.size <= 1)
            }
        }
    }

    @Test
    fun aRecipeWhoseCategoryLegsSpanTwoCurrenciesFailsTheContract() {
        val mixed = object : EntryRecipe<Transaction.Expense> by expenseRecipe() {
            override fun toLegs(entry: Transaction.Expense): EntryParts = expenseRecipe().toLegs(entry).let { parts ->
                val main = parts.legs.single { it.role == LegRole.MAIN }
                val other = Money.of(main.amount.amount, if (main.amount.currencyCode == "XTS") "XAU" else "XTS")
                parts.copy(legs = parts.legs + main.copy(position = 1, amount = other))
            }
        }
        assertTrue(violations(mixed).any { "category legs in more than one currency" in it })
    }

    @Test
    fun aDebtLegOnTheWrongSideBreaksTheBalanceCheck() {
        val owedToMe = object : EntryRecipe<Transaction.Expense> by expenseRecipe() {
            override fun toLegs(entry: Transaction.Expense): EntryParts = expenseRecipe().toLegs(entry).let { parts ->
                val main = parts.legs.single { it.role == LegRole.MAIN }
                parts.copy(legs = parts.legs + Leg(LegRole.DEBT, 0, LegTarget.Debt(1, DebtSide.OWED_TO_ME), main.amount))
            }
        }
        assertTrue(violations(owedToMe).any { "accounts + owed to me - I owe" in it })
    }

    @Test
    fun aRecipeThatOpensABalanceIsExemptFromTheBalanceCheckOnly() {
        val opening = object : EntryRecipe<Transaction.Expense> by expenseRecipe() {
            override val opensBalances: Boolean = true
            override fun toLegs(entry: Transaction.Expense): EntryParts = expenseRecipe().toLegs(entry).let { parts ->
                val main = parts.legs.single { it.role == LegRole.MAIN }
                parts.copy(legs = parts.legs + Leg(LegRole.DEBT, 0, LegTarget.Debt(1, DebtSide.I_OWE), main.amount))
            }
        }
        assertTrue(violations(opening).none { "accounts + owed to me - I owe" in it })
        assertTrue(EntryRecipes.all.none { it.opensBalances })
    }

    private fun expenseRecipe(): EntryRecipe<Transaction.Expense> {
        @Suppress("UNCHECKED_CAST")
        return EntryRecipes.forKind("EXPENSE") as EntryRecipe<Transaction.Expense>
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun EntryRecipe<*>.asAny(): EntryRecipe<Transaction> = this as EntryRecipe<Transaction>

        /** Copies of [parts] each damaged in one way a reader must refuse, named for the failure message. */
        fun damagedCopies(parts: EntryParts): List<Pair<String, EntryParts>> {
            val first = parts.legs.first()
            fun replaceFirst(leg: Leg) = parts.copy(legs = listOf(leg) + parts.legs.drop(1))
            val otherCurrency = if (first.amount.currencyCode == "XTS") "XAU" else "XTS"
            val wrongTarget: LegTarget = if (first.target is LegTarget.Account) LegTarget.Category(1, null) else LegTarget.Account(1)
            val copies = mutableListOf(
                "wrong sign on ${first.role}" to replaceFirst(first.copy(amount = -first.amount)),
                "wrong target on ${first.role}" to replaceFirst(first.copy(target = wrongTarget)),
                "position 1" to replaceFirst(first.copy(position = 1)),
                "a duplicate ${first.role}" to parts.copy(legs = parts.legs + first),
                "a rate on an account leg" to replaceFirst(
                    if (first.target is LegTarget.Account) first.copy(rateToBase = BigDecimal.ONE) else first,
                ).takeIf { first.target is LegTarget.Account },
                "header kind mismatch" to parts.copy(header = parts.header.copy(kind = "NOT_" + parts.header.kind)),
                // A transfer's two legs are allowed different currencies, so only the others must match.
                "currency mismatch" to replaceFirst(first.copy(amount = Money.of(first.amount.amount, otherCurrency)))
                    .takeIf { parts.header.kind != "TRANSFER" },
            ).mapNotNull { (name, copy) -> copy?.let { name to it } }.toMutableList()
            parts.legs.firstOrNull { it.target is LegTarget.Category }?.let { cat ->
                copies += "a rate of zero" to parts.copy(legs = parts.legs.map { if (it === cat) it.copy(rateToBase = BigDecimal.ZERO) else it })
            }
            if (parts.header.kind == "TRANSFER") {
                copies += "a transfer without a pair rate" to parts.copy(header = parts.header.copy(pairRate = null))
                copies += "a pair rate of zero" to parts.copy(header = parts.header.copy(pairRate = BigDecimal.ZERO))
                copies += "a negative destination" to parts.copy(
                    legs = parts.legs.map { if (it.role == LegRole.DEST) it.copy(amount = -it.amount) else it },
                )
            } else {
                copies += "a pair rate on ${parts.header.kind}" to parts.copy(header = parts.header.copy(pairRate = BigDecimal.ONE))
            }
            return copies
        }

        /** Every way [recipe] breaks the contract on its own examples; empty when it keeps it. */
        fun violations(recipe: EntryRecipe<*>): List<String> {
            val out = mutableListOf<String>()
            val examples = recipe.asAny().examples()
            if (examples.isEmpty()) out += "${recipe.kind}: no examples"
            examples.forEach { example ->
                val parts = recipe.asAny().toLegs(example)
                val label = "${recipe.kind} #${parts.header.id}"
                if (parts.header.kind != recipe.kind) out += "$label: header kind ${parts.header.kind}"
                val back = try {
                    recipe.asAny().fromLegs(parts)
                } catch (e: WalletException.UnreadableRow) {
                    null.also { out += "$label: its own legs read as unreadable" }
                }
                if (back != null && back != example) out += "$label: round trip changed the entry"
                parts.legs.groupBy { it.role to it.position }.filterValues { it.size > 1 }.keys
                    .forEach { out += "$label: two legs share $it" }
                parts.legs.filter { it.rateToBase != null && it.target !is LegTarget.Category }
                    .forEach { out += "$label: rate on a ${it.role} leg" }
                val categoryCurrencies = parts.legs.filter { it.target is LegTarget.Category }
                    .map { it.amount.currencyCode }.toSet()
                if (categoryCurrencies.size > 1) out += "$label: category legs in more than one currency $categoryCurrencies"
                val rules = recipe.asAny().trackingRules(example)
                parts.legs.filter { it.target is LegTarget.Category && it.role !in rules }
                    .forEach { out += "$label: no tracking rule for ${it.role}" }
                parts.legs.filter { it.target is LegTarget.Category }.forEach { leg ->
                    when (rules[leg.role]) {
                        TrackingRule.NOT_COUNTED -> out += "$label: ${leg.role} is a category leg that does not count"
                        TrackingRule.INCOME, TrackingRule.EXPENSE ->
                            if (leg.amount.isNegative) out += "$label: negative ${rules[leg.role]} leg ${leg.role}"
                        TrackingRule.REDUCES_EXPENSE ->
                            if (!leg.amount.isNegative) out += "$label: REDUCES_EXPENSE leg that is not negative ${leg.role}"
                        null -> Unit
                    }
                }
                if (!recipe.opensBalances && parts.legs.map { it.amount.currencyCode }.toSet().size == 1) {
                    val accounts = parts.legs.filter { it.target is LegTarget.Account }.sumOf { it.amount.amount }
                    val debts = parts.legs.mapNotNull { leg -> (leg.target as? LegTarget.Debt)?.let { it to leg } }
                        .sumOf { (debt, leg) ->
                            when (debt.side) {
                                DebtSide.OWED_TO_ME -> leg.amount.amount
                                DebtSide.I_OWE -> leg.amount.amount.negate()
                            }
                        }
                    val tracked = parts.legs.filter { it.target is LegTarget.Category }.sumOf { leg ->
                        when (rules[leg.role]) {
                            TrackingRule.INCOME -> leg.amount.amount
                            TrackingRule.EXPENSE, TrackingRule.REDUCES_EXPENSE -> leg.amount.amount.negate()
                            TrackingRule.NOT_COUNTED, null -> BigDecimal.ZERO
                        }
                    }
                    if ((accounts + debts).compareTo(tracked) != 0) {
                        out += "$label: accounts + owed to me - I owe ${accounts + debts} != income - expense $tracked"
                    }
                }
            }
            return out
        }
    }
}
