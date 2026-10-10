package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.ledger.EntryRecipes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EntryFormRuleKindsTest {
    @Test fun everyFormRuleKindIsARegisteredRecipeKind() {
        EntryFormRules.all.forEach { rule ->
            assertNotNull("no recipe for form rule ${rule.type}", EntryRecipes.forKind(rule.kind))
        }
    }

    @Test fun everyFormRuleKindIsItsTypeName() {
        EntryFormRules.all.forEach { assertEquals(it.type.name, it.kind) }
    }
}
