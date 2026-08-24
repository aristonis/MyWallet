package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCategoryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * A transaction's main category must be a top-level one, and its sub-category must be a child of
 * that exact category.
 *
 * Without the first rule a transaction can name a sub-category as its MAIN category, and deleting
 * that sub-category then leaves the row pointing at a row that no longer exists: the delete only
 * clears the finer `subCategoryId` column, and there is no foreign key on the main one. The money
 * silently drops out of every report and the confirmation still says nothing moved.
 */
class CategoryReferenceGuardTest {

    private val today = LocalDate.of(2026, 8, 20)

    private fun accounts() = FakeAccountRepository(
        listOf(
            Account(
                id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD",
                openingBalance = Money.of("100", "USD"),
            ),
        ),
    )

    /** 10 Food (top) -> 11 Groceries; 20 Salary (top) -> 21 Bonus. */
    private fun categories() = FakeCategoryRepository(
        listOf(
            Category(id = 10, name = "Food", kind = CategoryKind.EXPENSE),
            Category(id = 11, name = "Groceries", kind = CategoryKind.EXPENSE, parentId = 10),
            Category(id = 20, name = "Salary", kind = CategoryKind.INCOME),
            Category(id = 21, name = "Bonus", kind = CategoryKind.INCOME, parentId = 20),
        ),
    )

    private fun usd(a: String) = Money.of(a, "USD")

    @Test(expected = WalletException.CategoryNotTopLevel::class)
    fun anExpenseCannotUseASubCategoryAsItsMainCategory() = runTest {
        RecordExpense(accounts(), categories(), FakeTransactionRepository())
            .invoke(accountId = 1, amount = usd("12.99"), categoryId = 11, date = today)
    }

    @Test(expected = WalletException.CategoryNotTopLevel::class)
    fun anIncomeCannotUseASubCategoryAsItsMainCategory() = runTest {
        RecordIncome(accounts(), categories(), FakeTransactionRepository())
            .invoke(accountId = 1, amount = usd("500"), categoryId = 21, date = today)
    }

    @Test
    fun aRejectedCategoryReferenceStoresNothing() = runTest {
        val txs = FakeTransactionRepository()
        try {
            RecordExpense(accounts(), categories(), txs)
                .invoke(accountId = 1, amount = usd("12.99"), categoryId = 11, date = today)
        } catch (_: WalletException.CategoryNotTopLevel) {
            // expected
        }
        assertEquals(0, txs.added.size)
    }

    @Test
    fun aTopLevelCategoryWithAMatchingChildIsAccepted() = runTest {
        val txs = FakeTransactionRepository()
        RecordExpense(accounts(), categories(), txs)
            .invoke(accountId = 1, amount = usd("12.99"), categoryId = 10, date = today, subCategoryId = 11)

        assertEquals(1, txs.added.size)
    }

    @Test
    fun aTopLevelCategoryWithNoSubCategoryIsAccepted() = runTest {
        val txs = FakeTransactionRepository()
        RecordExpense(accounts(), categories(), txs)
            .invoke(accountId = 1, amount = usd("12.99"), categoryId = 10, date = today)

        assertEquals(1, txs.added.size)
    }

    @Test(expected = WalletException.CategoryKindMismatch::class)
    fun aSubCategoryBelongingToAnotherParentIsRejected() = runTest {
        // 21 is a child of Salary, not of Food — accepting it would file the spend under a label
        // that no breakdown could ever resolve against its own parent.
        RecordExpense(accounts(), categories(), FakeTransactionRepository())
            .invoke(accountId = 1, amount = usd("12.99"), categoryId = 10, date = today, subCategoryId = 21)
    }

    @Test(expected = WalletException.CategoryNotFound::class)
    fun anUnknownSubCategoryFailsLoud() = runTest {
        RecordExpense(accounts(), categories(), FakeTransactionRepository())
            .invoke(accountId = 1, amount = usd("12.99"), categoryId = 10, date = today, subCategoryId = 99)
    }
}
