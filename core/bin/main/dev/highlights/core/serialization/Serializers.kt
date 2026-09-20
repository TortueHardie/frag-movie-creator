package dev.highlights.core.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration

typealias SerialDuration = @Serializable(with = HumanDurationSerializer::class) Duration
typealias SerialPath = @Serializable(with = PathSerializer::class) Path
typealias SerialInstant = @Serializable(with = InstantSerializer::class) Instant

/** Durées lisibles dans les fichiers de config : "2s", "1.5s", "300ms", "1m30s", "01:23.5", ISO-8601. */
object HumanDurationSerializer : KSerializer<Duration> {
    override val descriptor = PrimitiveSerialDescriptor("dev.highlights.Duration", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeString(Durations.format(value))

    override fun deserialize(decoder: Decoder): Duration {
        val text = decoder.decodeString()
        return Durations.parseOrNull(text) ?: throw SerializationException(
            "Durée invalide '$text' (exemples acceptés : 2s, 1.5s, 300ms, 1m30s, 01:23.5)",
        )
    }
}

object PathSerializer : KSerializer<Path> {
    override val descriptor = PrimitiveSerialDescriptor("dev.highlights.Path", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Path) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Path = Path(decoder.decodeString())
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("dev.highlights.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
