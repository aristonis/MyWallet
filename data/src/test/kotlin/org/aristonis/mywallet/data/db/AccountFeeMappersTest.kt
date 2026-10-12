package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.FeeRule
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class AccountFeeMappersTest {

    private fun row(mode: String?, value: String?) = AccountEntity(
        id = 17,
        name = "Visa",
        typeKey = "credit_card",
        currencyCode = "USD",
        openingBalanceAmount = "0",
        defaultFeeMode = mode,
        defaultFeeValue = value,
    )

    private fun assertUnreadable(row: AccountEntity) {
        val thrown = assertThrows(WalletException.UnreadableRow::class.java) { row.toDomain() }

        assertEquals(17L, thrown.id)
        assertEquals("account", thrown.kind)
        assertTrue("cause was ${thrown.cause}", thrown.cause != null)
    }

    @Test
    fun fixedDefaultFeeRoundTripsExactly() {
        val account = Account(id = 4, name = "Wallet", typeKey = "card", currencyCode = "EUR",
            openingBalance = Money.zero("EUR"), defaultFee = FeeRule(FeeMode.FIXED, BigDecimal("3.500")))
        assertEquals(account, account.toEntity().toDomain())
        assertEquals("FIXED", account.toEntity().defaultFeeMode)
        assertEquals("3.500", account.toEntity().defaultFeeValue)
    }

    @Test
    fun defaultFeeRoundTripsWithItsExactValueText() {
        val account = Account(
            id = 3, name = "Visa", typeKey = "credit_card", currencyCode = "USD",
            openingBalance = Money.zero("USD"), defaultFee = FeeRule(FeeMode.PERCENT, BigDecimal("1.250")),
        )

        val entity = account.toEntity()

        assertEquals("PERCENT", entity.defaultFeeMode)
        assertEquals("1.250", entity.defaultFeeValue)
        assertEquals(account, entity.toDomain())
    }

    @Test
    fun anAccountWithNoFeeColumnsHasNoDefaultFee() {
        assertEquals(null, row(mode = null, value = null).toDomain().defaultFee)
    }

    @Test
    fun anUnknownStoredFeeModeIsReportedAsAnUnreadableRow() = assertUnreadable(row("HALF", "1"))

    @Test
    fun aNonNumericStoredFeeValueIsReportedAsAnUnreadableRow() = assertUnreadable(row("PERCENT", "abc"))

    @Test
    fun aNegativeStoredFeeValueIsReportedAsAnUnreadableRow() = assertUnreadable(row("FIXED", "-1"))

    @Test
    fun aFeeModeWithoutAValueIsReportedAsAnUnreadableRow() = assertUnreadable(row("FIXED", null))

    @Test
    fun aFeeValueWithoutAModeIsReportedAsAnUnreadableRow() = assertUnreadable(row(null, "2"))
}
