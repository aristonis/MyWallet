package org.aristonis.mywallet.ui.message

import org.aristonis.mywallet.data.format.MoneyParseError
import org.aristonis.mywallet.data.format.MoneyParseException
import org.aristonis.mywallet.domain.error.WalletException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Every failure that can reach a screen has to arrive as a named message. The dangerous case is the
 * quiet one: an exception nobody mapped falling through and putting a developer's English sentence —
 * or a raw row id — in front of the user.
 */
class UiMessageMappingTest {

    @Test
    fun `each domain error becomes its own message`() {
        assertEquals(UiMessage.AccountNotFound, WalletException.AccountNotFound(7).toUiMessage())
        assertEquals(UiMessage.AccountInUse, WalletException.AccountInUse(7).toUiMessage())
        assertEquals(UiMessage.AccountCurrencyLocked, WalletException.AccountCurrencyLocked(7).toUiMessage())
        assertEquals(UiMessage.AccountArchived, WalletException.AccountArchived(7).toUiMessage())
        assertEquals(UiMessage.CategoryNotFound, WalletException.CategoryNotFound(7).toUiMessage())
        assertEquals(UiMessage.CategoryDepthExceeded, WalletException.CategoryDepthExceeded(7).toUiMessage())
        assertEquals(UiMessage.CategoryKindMismatch, WalletException.CategoryKindMismatch(7).toUiMessage())
        assertEquals(UiMessage.CategoryStructureInvalid, WalletException.CategoryStructureInvalid(7).toUiMessage())
        assertEquals(UiMessage.CategoryNotTopLevel, WalletException.CategoryNotTopLevel(7).toUiMessage())
        assertEquals(UiMessage.SystemCategoryProtected, WalletException.SystemCategoryProtected(7).toUiMessage())
        assertEquals(UiMessage.TransactionNotFound, WalletException.TransactionNotFound(7).toUiMessage())
        assertEquals(
            UiMessage.TransferCurrencyPairChanged,
            WalletException.TransferCurrencyPairChanged(7).toUiMessage(),
        )
        assertEquals(UiMessage.BackupNotRecognized, WalletException.BackupInvalid().toUiMessage())
        assertEquals(UiMessage.BackupFromNewerVersion, WalletException.BackupVersionUnsupported(9).toUiMessage())
    }

    @Test
    fun `the values a sentence needs survive the mapping`() {
        assertEquals(UiMessage.MissingRate("EUR"), WalletException.MissingRate("EUR").toUiMessage())
        assertEquals(UiMessage.CurrencyNotFound("XYZ"), WalletException.CurrencyNotFound("XYZ").toUiMessage())
        assertEquals(UiMessage.RateUnderflow("JPY"), WalletException.RateUnderflow("JPY").toUiMessage())
        assertEquals(UiMessage.AmountRoundsToZero("KWD"), WalletException.AmountRoundsToZero("KWD").toUiMessage())
        assertEquals(
            UiMessage.CurrencyMismatch("EUR", "USD"),
            WalletException.CurrencyMismatch("EUR", "USD").toUiMessage(),
        )
        assertEquals(
            UiMessage.DuplicateCategoryName("Food"),
            WalletException.DuplicateCategoryName("Food").toUiMessage(),
        )
    }

    /**
     * A row id is an internal detail. It must not ride along into the message, or a translator ends
     * up with "%d" holes for numbers the user has no use for.
     */
    @Test
    fun `an internal row id never reaches the message`() {
        assertEquals(
            WalletException.AccountNotFound(11).toUiMessage(),
            WalletException.AccountNotFound(4098).toUiMessage(),
        )
    }

    @Test
    fun `each parse failure becomes its own message`() {
        assertEquals(UiMessage.AmountMissing, MoneyParseError.AMOUNT_MISSING.toUiMessage())
        assertEquals(UiMessage.AmountNotPositive, MoneyParseError.AMOUNT_NOT_POSITIVE.toUiMessage())
        assertEquals(UiMessage.AmountOutOfRange, MoneyParseError.AMOUNT_OUT_OF_RANGE.toUiMessage())
        assertEquals(UiMessage.RateMissing, MoneyParseError.RATE_MISSING.toUiMessage())
        assertEquals(UiMessage.RateNotPositive, MoneyParseError.RATE_NOT_POSITIVE.toUiMessage())
        assertEquals(UiMessage.RateOutOfRange, MoneyParseError.RATE_OUT_OF_RANGE.toUiMessage())
        assertEquals(UiMessage.NotANumber, MoneyParseError.NOT_A_NUMBER.toUiMessage())
    }

    @Test
    fun `no two parse failures collapse onto the same message`() {
        val messages = MoneyParseError.entries.map { it.toUiMessage() }

        assertEquals(MoneyParseError.entries.size, messages.toSet().size)
    }

    @Test
    fun `a rejected amount arrives as its named cause, not as an exception message`() {
        val thrown: Throwable = MoneyParseException(MoneyParseError.AMOUNT_NOT_POSITIVE)

        assertEquals(UiMessage.AmountNotPositive, thrown.toUiMessage())
    }

    /**
     * The catch-all is a floor, not a hiding place: anything unmapped becomes a plain apology rather
     * than a developer's sentence.
     */
    @Test
    fun `an unmapped failure falls back to the generic message`() {
        val thrown: Throwable = IllegalStateException("Room could not open the database")

        assertEquals(UiMessage.Unexpected, thrown.toUiMessage())
        assertNotEquals(UiMessage.Unexpected, WalletException.AccountArchived(1).toUiMessage())
    }
}
