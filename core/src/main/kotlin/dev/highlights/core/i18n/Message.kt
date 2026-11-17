package dev.highlights.core.i18n

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Texte à traduire au moment où on l'affiche : une clé des bundles et ses arguments. C'est ce que portent les
 * exceptions ([dev.highlights.core.HighlightsException.text]) et les avertissements d'une session : le code qui
 * détecte le problème ne connaît pas la langue de l'interface (et une session enregistrée se rouvre peut-être dans une
 * autre langue) ; l'interface traduit avec [text], les journaux prennent l'anglais ([english]).
 *
 * Arguments : nombres, textes, [Duration], [java.time.LocalDate], chemins, autres [Message] (traduits à leur tour).
 * L'argument `count` choisit la forme plurielle. Une clé vide ([raw]) porte un texte déjà écrit, rendu tel quel : les
 * messages venus de l'extérieur (Google, FFmpeg) et les avertissements des sessions d'avant les traductions.
 */
@Serializable(with = MessageSerializer::class)
class Message(val key: String, val args: Map<String, Any?> = emptyMap()) {
    constructor(key: String, vararg args: Pair<String, Any?>) : this(key, args.toMap())

    /** Texte dans la langue de l'interface (ou [locale]). */
    fun text(locale: Locale = I18n.locale): String =
        if (key.isEmpty()) args["text"]?.toString().orEmpty() else I18n.text(key, args, locale)

    /** Texte anglais, pour les journaux et le message technique des exceptions. */
    fun english(): String = text(I18n.ENGLISH)

    override fun toString(): String = english()

    override fun equals(other: Any?): Boolean = other is Message && other.key == key && other.args == args
    override fun hashCode(): Int = key.hashCode() * 31 + args.hashCode()

    companion object {
        /** Texte déjà écrit, non traduit. */
        fun raw(text: String) = Message("", mapOf("text" to text))
    }
}

/**
 * JSON d'un [Message] : `{"key": "...", "args": {...}}`, chaque argument en nombre, texte, booléen ou message imbriqué ;
 * une durée part en millisecondes sous `{"ms": …}`. Un texte nu (ancien format des avertissements) se relit en [Message.raw].
 */
object MessageSerializer : KSerializer<Message> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("dev.highlights.core.i18n.Message")

    override fun serialize(encoder: Encoder, value: Message) {
        (encoder as JsonEncoder).encodeJsonElement(toJson(value))
    }

    override fun deserialize(decoder: Decoder): Message = fromJson((decoder as JsonDecoder).decodeJsonElement())

    private fun toJson(m: Message): JsonElement = JsonObject(
        mapOf("key" to JsonPrimitive(m.key), "args" to JsonObject(m.args.mapValues { (_, v) -> argToJson(v) })),
    )

    private fun argToJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Message -> toJson(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Duration -> JsonObject(mapOf("ms" to JsonPrimitive(v.inWholeMilliseconds)))
        is Collection<*> -> JsonArray(v.map(::argToJson))
        else -> JsonPrimitive(v.toString())
    }

    private fun fromJson(e: JsonElement): Message = when (e) {
        is JsonPrimitive -> Message.raw(e.contentOrNull.orEmpty())
        is JsonObject -> Message(
            e["key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            e["args"]?.jsonObject?.mapValues { (_, v) -> argFromJson(v) }.orEmpty(),
        )
        else -> Message.raw(e.toString())
    }

    private fun argFromJson(e: JsonElement): Any? = when (e) {
        JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            else -> e.doubleOrNull
        }
        is JsonObject -> if (e.keys == setOf("ms")) {
            (e.getValue("ms").jsonPrimitive.longOrNull ?: 0L).milliseconds
        } else {
            fromJson(e)
        }
        is JsonArray -> e.map(::argFromJson)
    }
}
