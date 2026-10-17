package org.aristonis.mywallet.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.TransactionFilter
import org.aristonis.mywallet.ui.format.AppLocale
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.formatterFor
import org.aristonis.mywallet.ui.format.FULL_DATE_SKELETON
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.transaction.TransactionDetailContent
import org.aristonis.mywallet.ui.transaction.TransactionDetailUiState
import org.aristonis.mywallet.ui.transaction.TransactionFilterSheet
import org.aristonis.mywallet.ui.transaction.TransactionRow
import org.aristonis.mywallet.ui.transaction.TransactionRowItem
import org.aristonis.mywallet.ui.transaction.TransactionRowType
import org.aristonis.mywallet.ui.transaction.TransactionsListContent
import org.aristonis.mywallet.ui.transaction.TransactionsUiState
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Run with the actual device language set to Arabic; font scale alone is overridden here. */
@RunWith(AndroidJUnit4::class)
class ArabicScreensTest {
    @get:Rule val compose = createComposeRule()

    private val account = Account(1, "Bank حساب", "BANK", "USD", Money.zero("USD"))
    private val category = Category(1, "Interest فائدة", CategoryKind.EXPENSE)
    private val child = Category(2, "Monthly شهري", CategoryKind.EXPENSE, parentId = 1)
    private val note = "فاتورة Invoice 42"
    private val row = TransactionRow(
        id = 1, date = LocalDate.of(2026, 10, 17), type = TransactionRowType.EXPENSE,
        accountName = account.name, destAccountName = null,
        categoryLabel = CategoryLabel.Named(category.name), amount = Money.of("1000", "USD"),
        destAmount = null, amountDisplay = "1,000.00 USD", destAmountDisplay = null,
        note = note, feeDisplay = "3.00 USD",
    )

    @Test fun transactionRowInArabicAtNormalText() = checkRow(1f)
    @Test fun transactionRowInArabicAtLargeText() = checkRow(2f)
    @Test fun filterChecklistInArabicAtNormalText() = checkChecklist(1f)
    @Test fun filterChecklistInArabicAtLargeText() = checkChecklist(2f)

    @Test
    fun multipleFilterNamesRemainSeparateInArabicEmptyMessage() {
        val context = arabicContext()
        show(context, 2f) {
            TransactionsListContent(
                state = TransactionsUiState(
                    isLoading = false, filter = TransactionFilter(accountIds = setOf(1), categoryIds = setOf(1)),
                    filterAccounts = listOf(account), filterCategories = listOf(category),
                ),
                windowActions = DateWindowActions.None, onAddTransaction = {}, onOpenTransaction = {},
                onShowSavedEntry = {}, onSavedEntryMessageDone = {}, onRetry = {},
            )
        }
        val names = "${bidiIsolate(category.name)} · ${bidiIsolate(account.name)}"
        compose.onNodeWithText(context.getString(R.string.transaction_filter_empty_combined, names)).assertIsDisplayed()
        screenshot(context, "empty-multiple-filters-200")
    }

    @Test
    fun detailDeleteTargetIsolatesNameAmountAndDateSeparately() {
        val context = arabicContext()
        show(context, 2f) {
            TransactionDetailContent(
                state = TransactionDetailUiState(isLoading = false, row = row),
                onBack = {}, onRetry = {}, onEdit = {}, onDelete = {}, onUndoDelete = {}, onConfirmDelete = {},
            )
        }
        compose.onNodeWithTag("transaction-detail-delete").performScrollTo().performClick()
        val date = formatterFor(FULL_DATE_SKELETON, context.resources.configuration.locales[0]).format(row.date)
        val target = context.getString(
            R.string.transaction_detail_delete_target,
            bidiIsolate(category.name), bidiIsolate("−1,000.00 USD"), bidiIsolate(date),
        )
        compose.onNodeWithText(target).assertIsDisplayed()
        screenshot(context, "detail-delete-200")
    }

    private fun checkRow(scale: Float) {
        val context = arabicContext()
        show(context, scale) { TransactionRowItem(row, onClick = {}) }
        val headline = context.getString(R.string.transaction_headline_with_note, bidiIsolate(category.name), bidiIsolate(note))
        val node = compose.onNodeWithText(headline, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val viewport = compose.onNodeWithTag("arabic-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(node.boundsInRoot.left >= viewport.left && node.boundsInRoot.right <= viewport.right)
        compose.onNodeWithText("−1,000.00 USD", useUnmergedTree = true).assertIsDisplayed()
        screenshot(context, "transaction-row-${(scale * 100).toInt()}")
    }

    private fun checkChecklist(scale: Float) {
        val context = arabicContext()
        show(context, scale) {
            TransactionFilterSheet(
                filter = TransactionFilter(accountIds = setOf(1, 2), categoryIds = setOf(1, 2)),
                accounts = listOf(account), categories = listOf(category, child),
                onAccount = {}, onClearAccounts = {}, onCategory = {}, onClearCategories = {},
                onClear = {}, onDismiss = {},
            )
        }
        val label = context.getString(R.string.transaction_filter_category_child, bidiIsolate(category.name), bidiIsolate(child.name))
        compose.onNodeWithText(label).assertExists()
        screenshot(context, "filter-checklist-${(scale * 100).toInt()}")
    }

    private fun arabicContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val incoming = base.resources.configuration
        assumeTrue("Set the device language to Arabic before this check", incoming.locales[0].language == "ar")
        return base.createConfigurationContext(Configuration(incoming).apply {
            setLocale(AppLocale.from(incoming.locales[0]))
        })
    }

    private fun show(context: Context, scale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            assertEquals(LayoutDirection.Rtl, LocalLayoutDirection.current)
            CompositionLocalProvider(
                LocalContext provides context, LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(density.density, scale),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    Box(Modifier.fillMaxSize().testTag("arabic-viewport")) { content() }
                }
            }
        }
    }

    private fun screenshot(context: Context, name: String) {
        val directory = checkNotNull(context.getExternalFilesDir("arabic-screens"))
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
