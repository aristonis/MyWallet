package org.aristonis.mywallet.data.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * kotlinx.serialization ships no serializer for [java.time.LocalDate], so store it as an ISO-8601
 * string (e.g. "2026-01-05"): human-readable, locale-independent, and stable across app versions.
 */
object LocalDateSerializer : KSerializer<LocalDate> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) =
        encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalDate {
        val raw = decoder.decodeString()
        // Re-throw a parse failure as SerializationException so the codec surfaces every corrupt
        // date (a truncated or hand-edited file) as one typed error, not a stray DateTimeParseException.
        return try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw SerializationException("invalid ISO-8601 date: \"$raw\"", e)
        }
    }
}
