package org.aristonis.mywallet.domain.ledger.recipe

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

/**
 * The one place a recipe's stored legs are checked for shape. Later kinds have legs that may be
 * absent (a fee of zero) or repeated (one per deduction), so the shape is declared, and a repeated
 * role must come at positions 0, 1, 2 with no gap: a gap or a duplicate is a lost or doubled leg,
 * never something to read past.
 */
class ReadPartsTest {

    private val shape = LegShape(
        required = setOf(LegRole.ACCOUNT, LegRole.MAIN),
        optional = setOf(LegRole.FEE),
        repeated = setOf(LegRole.DEDUCTION),
        perLine = setOf(LegRole.DEDUCTION_DEBT),
    )

    private fun leg(role: LegRole, position: Int = 0) = Leg(role, position, LegTarget.Category(1, null), Money.of("1", "USD"))
    private fun parts(vararg legs: Leg) = EntryParts(EntryHeader(5, "INCOME", LocalDate.of(2026, 1, 1), null), legs.toList())
    private fun read(vararg legs: Leg) = readParts(parts(*legs), "INCOME", shape) { byRole -> byRole.mapValues { (_, l) -> l.map { it.position } } }

    @Test
    fun requiredLegsAloneAreReadable() {
        assertEquals(mapOf(LegRole.ACCOUNT to listOf(0), LegRole.MAIN to listOf(0)), read(leg(LegRole.ACCOUNT), leg(LegRole.MAIN)))
    }

    @Test
    fun optionalAndRepeatedLegsAreHandedOverInPositionOrder() {
        val result = read(
            leg(LegRole.MAIN), leg(LegRole.DEDUCTION, 1), leg(LegRole.ACCOUNT), leg(LegRole.FEE), leg(LegRole.DEDUCTION, 0),
        )
        assertEquals(listOf(0, 1), result.getValue(LegRole.DEDUCTION))
        assertEquals(listOf(0), result.getValue(LegRole.FEE))
    }

    @Test
    fun aMissingRequiredLegIsUnreadable() = assertUnreadable(leg(LegRole.ACCOUNT))

    @Test
    fun aRequiredLegTwiceIsUnreadable() = assertUnreadable(leg(LegRole.ACCOUNT), leg(LegRole.MAIN), leg(LegRole.MAIN))

    @Test
    fun anOptionalLegNotAtPositionZeroIsUnreadable() = assertUnreadable(leg(LegRole.ACCOUNT), leg(LegRole.MAIN), leg(LegRole.FEE, 1))

    @Test
    fun aGapInARepeatedRoleIsUnreadable() =
        assertUnreadable(leg(LegRole.ACCOUNT), leg(LegRole.MAIN), leg(LegRole.DEDUCTION, 0), leg(LegRole.DEDUCTION, 2))

    @Test
    fun aRepeatedPositionIsUnreadable() =
        assertUnreadable(leg(LegRole.ACCOUNT), leg(LegRole.MAIN), leg(LegRole.DEDUCTION, 0), leg(LegRole.DEDUCTION, 0))

    @Test
    fun aRoleTheShapeDoesNotNameIsUnreadable() = assertUnreadable(leg(LegRole.ACCOUNT), leg(LegRole.MAIN), leg(LegRole.LOSS))

    @Test
    fun aRejectedValueInsideTheReaderIsUnreadable() {
        assertThrows(WalletException.UnreadableRow::class.java) {
            readParts(parts(leg(LegRole.ACCOUNT), leg(LegRole.MAIN)), "INCOME", shape) { require(false) { "bad value" } }
        }
    }

    @Test
    fun aBugInsideTheReaderIsNotPassedOffAsBadData() {
        assertThrows(IllegalStateException::class.java) {
            readParts(parts(leg(LegRole.ACCOUNT), leg(LegRole.MAIN)), "INCOME", shape) { error("a recipe bug") }
        }
    }

    /** Only lines owed later have a debt leg, so these positions follow the lines and may skip some. */
    @Test
    fun perLineLegsMaySkipPositionsButComeInOrder() {
        val result = read(leg(LegRole.ACCOUNT), leg(LegRole.MAIN), leg(LegRole.DEDUCTION_DEBT, 2), leg(LegRole.DEDUCTION_DEBT, 1))
        assertEquals(listOf(1, 2), result.getValue(LegRole.DEDUCTION_DEBT))
    }

    @Test
    fun aPerLinePositionTwiceIsUnreadable() =
        assertUnreadable(leg(LegRole.ACCOUNT), leg(LegRole.MAIN), leg(LegRole.DEDUCTION_DEBT, 1), leg(LegRole.DEDUCTION_DEBT, 1))

    @Test
    fun aNegativePerLinePositionIsUnreadable() =
        assertUnreadable(leg(LegRole.ACCOUNT), leg(LegRole.MAIN), leg(LegRole.DEDUCTION_DEBT, -1))

    private fun assertUnreadable(vararg legs: Leg) {
        val error = assertThrows(WalletException.UnreadableRow::class.java) { read(*legs) }
        assertEquals(5L, error.id)
        assertEquals("INCOME", error.kind)
    }
}
