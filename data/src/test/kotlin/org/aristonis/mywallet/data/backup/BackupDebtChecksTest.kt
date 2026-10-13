package org.aristonis.mywallet.data.backup

import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What a restore does with debt rows, deduction item keys and credit limits: keys are recomputed from
 * the stored names, and a file that breaks a debt or limit rule is refused as invalid.
 *
 * The debt-leg cases use the OWED_BEFORE and REPAYMENT kinds, so each entry is readable on its own
 * and only the debt's sum can make a file invalid.
 */
class BackupDebtChecksTest {

    private val sample = BackupV2Samples.wallet()

    private fun debt(
        id: Long,
        name: String,
        key: String,
        side: String = "I_OWE",
        currency: String = "USD",
    ) = DebtDto(id, name, key, side, currency, dueDate = null, note = null)

    private fun roundTrip(backup: WalletBackupV2): WalletBackupV2 = decodeValidated(BackupCodec.encode(backup))

    private fun assertInvalid(backup: WalletBackupV2) {
        val thrown = assertThrows(WalletException::class.java) { decodeValidated(BackupCodec.encode(backup)) }
        assertTrue(thrown is WalletException.BackupInvalid)
    }

    private fun deductionItem(id: Long, name: String, key: String) = DeductionItemDto(
        id = id, name = name, nameKey = key, mode = "PERCENT", value = "10", valueCurrencyCode = null,
        timing = "NOW", expenseCategoryId = BackupV2Samples.TAX, enabled = true, sortOrder = 0,
    )

    // --- Debt keys ---

    @Test
    fun aDebtWithAStaleKeyRestoresWithTheRecomputedKey() {
        val file = sample.copy(debts = listOf(debt(1, "Ali", key = "stale-key")))

        assertEquals(listOf("ali"), roundTrip(file).debts.map { it.counterpartyKey })
    }

    @Test
    fun aDebtRestoresWithItsStoredCounterpartyTrimmed() {
        val file = sample.copy(debts = listOf(debt(1, "  Ali ", key = "stale")))

        val restored = roundTrip(file).debts.single()

        assertEquals("Ali", restored.counterparty)
        assertEquals("ali", restored.counterpartyKey)
    }

    @Test
    fun aDebtWhoseKeyWasWrittenByAnOlderFoldingGetsTheCurrentOne() {
        val arabic = "\u0623\u062D\u0645\u062F"
        val file = sample.copy(debts = listOf(debt(1, arabic, key = arabic)))

        assertEquals("\u0627\u062D\u0645\u062F", roundTrip(file).debts.single().counterpartyKey)
    }

    @Test
    fun twoDebtsWhoseNamesFoldToOneKeyOnTheSameSideAndCurrencyAreInvalid() {
        assertInvalid(sample.copy(debts = listOf(debt(1, "Ali", key = "ali"), debt(2, " ALI ", key = "other"))))
    }

    @Test
    fun theSameNameOnAnotherSideOrCurrencyIsStillAccepted() {
        val file = sample.copy(
            debts = listOf(
                debt(1, "Ali", key = "ali"),
                debt(2, "ali", key = "x", side = "OWED_TO_ME"),
                debt(3, "ali", key = "y", currency = "EUR"),
            ),
        )

        assertEquals(3, roundTrip(file).debts.size)
    }

    @Test
    fun aDebtWithABlankNameIsInvalid() {
        assertInvalid(sample.copy(debts = listOf(debt(1, "   ", key = ""))))
    }

    // --- Debt balances ---

    @Test
    fun aDebtWithNoLegsIsAccepted() {
        assertEquals(1, roundTrip(sample.copy(debts = listOf(debt(1, "Ali", key = "ali")))).debts.size)
    }

    @Test
    fun aDebtWhoseLegsAddUpToMoreThanZeroIsAccepted() {
        val file = sample.copy(
            debts = listOf(debt(1, "Ali", key = "ali")),
            entries = sample.entries + EntryDto(20, "OWED_BEFORE", LocalDate.of(2026, 1, 8), note = null, pairRate = null),
            legs = sample.legs + debtLeg(entryId = 20, debtId = 1, amount = "5.00"),
        )

        assertEquals(1, roundTrip(file).debts.size)
    }

    @Test
    fun aDebtWhoseLegsAddUpToBelowZeroIsInvalid() {
        // Each entry reads fine on its own: 5.00 was owed, then 8.00 was paid from Cash. Only the sum
        // shows the debt paid past zero, so the refusal must come from the debt check.
        val file = sample.copy(
            debts = listOf(debt(1, "Ali", key = "ali")),
            entries = sample.entries +
                EntryDto(20, "OWED_BEFORE", LocalDate.of(2026, 1, 8), note = null, pairRate = null) +
                EntryDto(21, "REPAYMENT", LocalDate.of(2026, 1, 9), note = null, pairRate = null),
            legs = sample.legs +
                debtLeg(entryId = 20, debtId = 1, amount = "5.00") +
                LegDto(
                    entryId = 21, role = "ACCOUNT", position = 0, accountId = BackupV2Samples.CASH, debtId = null,
                    categoryId = null, subCategoryId = null, amount = "-8.00", currencyCode = "USD", rateToBase = null,
                ) +
                debtLeg(entryId = 21, debtId = 1, amount = "-8.00"),
        )

        assertInvalid(file)
    }

    @Test
    fun theSameEntriesPayingNoMoreThanWasOwedAreAccepted() {
        val file = sample.copy(
            debts = listOf(debt(1, "Ali", key = "ali")),
            entries = sample.entries +
                EntryDto(20, "OWED_BEFORE", LocalDate.of(2026, 1, 8), note = null, pairRate = null) +
                EntryDto(21, "REPAYMENT", LocalDate.of(2026, 1, 9), note = null, pairRate = null),
            legs = sample.legs +
                debtLeg(entryId = 20, debtId = 1, amount = "5.00") +
                LegDto(
                    entryId = 21, role = "ACCOUNT", position = 0, accountId = BackupV2Samples.CASH, debtId = null,
                    categoryId = null, subCategoryId = null, amount = "-5.00", currencyCode = "USD", rateToBase = null,
                ) +
                debtLeg(entryId = 21, debtId = 1, amount = "-5.00"),
        )

        assertEquals(1, roundTrip(file).debts.size)
    }

    private fun debtLeg(entryId: Long, debtId: Long, amount: String) = LegDto(
        entryId = entryId, role = "DEBT", position = 0, accountId = null, debtId = debtId,
        categoryId = null, subCategoryId = null, amount = amount, currencyCode = "USD", rateToBase = null,
    )

    // --- Deduction item keys ---

    @Test
    fun aDeductionItemWithAStaleKeyRestoresWithTheRecomputedKey() {
        val file = sample.copy(deductionItems = listOf(deductionItem(1, "  Income Tax ", key = "stale")))

        assertEquals(listOf("income tax"), roundTrip(file).deductionItems.map { it.nameKey })
    }

    // --- Credit limits ---

    private fun withLimit(limit: String?) = sample.copy(
        accounts = sample.accounts + AccountDtoV2(
            id = 9, name = "Card", typeKey = "credit_card", currencyCode = "USD",
            openingBalanceAmount = "0.00", creditLimit = limit,
        ),
    )

    @Test
    fun aValidCreditLimitRestoresAsWritten() {
        val restored = roundTrip(withLimit("1500.00")).accounts.single { it.id == 9L }

        assertEquals("1500.00", restored.creditLimit)
    }

    @Test
    fun aCardWithNoLimitStillRestores() {
        assertEquals(null, roundTrip(withLimit(null)).accounts.single { it.id == 9L }.creditLimit)
    }

    @Test
    fun aCreditLimitThatDoesNotParseIsInvalid() = assertInvalid(withLimit("lots"))

    @Test
    fun aCreditLimitOfZeroIsInvalid() = assertInvalid(withLimit("0.00"))

    @Test
    fun aNegativeCreditLimitIsInvalid() = assertInvalid(withLimit("-500.00"))
}
