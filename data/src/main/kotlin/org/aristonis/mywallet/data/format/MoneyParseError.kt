package org.aristonis.mywallet.data.format

/**
 * Why a typed amount or rate was rejected.
 *
 * This layer has no resources and no idea what language the user reads, so it names the fault
 * instead of wording it. The UI decides what to say — which is also what lets the same rejection be
 * phrased one way under an amount field and another way under a rate field.
 */
enum class MoneyParseError {
    /** An amount is required and the field was blank. */
    AMOUNT_MISSING,

    /** An amount must be strictly positive. */
    AMOUNT_NOT_POSITIVE,

    /** The exponent is so extreme that rendering the number would exhaust memory. */
    AMOUNT_OUT_OF_RANGE,

    /** A rate is required and the field was blank. */
    RATE_MISSING,

    /** A rate must be strictly positive. */
    RATE_NOT_POSITIVE,

    /** As [AMOUNT_OUT_OF_RANGE], for a rate. */
    RATE_OUT_OF_RANGE,

    /** The text is not a number in this locale, or is only partly one. */
    NOT_A_NUMBER,
}

/**
 * A rejected amount or rate.
 *
 * Still an [IllegalArgumentException] so existing callers that catch broadly keep working; the
 * [error] is what callers should actually branch on. The exception message is the enum name — a
 * developer-facing token for a stack trace, never something to put in front of a user.
 */
class MoneyParseException(val error: MoneyParseError) : IllegalArgumentException(error.name)
