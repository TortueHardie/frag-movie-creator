package dev.highlights.ui.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.TemporalAccessor
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.jvmErasure
import kotlin.time.Duration

/**
 * Conversion entre objets de l'interface et JSON, par réflexion Kotlin : l'état ([dev.highlights.ui.UiState]) n'a pas à
 * être @Serializable (il contient des Path, des Duration, des sessions entières), et une action ajoutée à
 * [dev.highlights.ui.UiActions] est pilotable sans rien écrire ici.
 *
 * Sortie volontairement bornée : tableaux de nombres résumés (une courbe de score fait des milliers de valeurs),
 * listes tronquées, profondeur limitée. Seules les classes de l'application (`dev.highlights.*`) sont parcourues
 * propriété par propriété (propriétés calculées comprises : `canAnalyze`, `canCreate`… disent ce que l'UI permet) ;
 * le reste est rendu par `toString()`.
 */
object BridgeJson {
    /** Limites par défaut d'un vidage d'état. */
    data class Limits(val depth: Int = 10, val list: Int = 60, val numbers: Int = 16)

    fun toJson(value: Any?, limits: Limits = Limits()): JsonElement = encode(value, limits, 0)

    private fun encode(value: Any?, limits: Limits, depth: Int): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> encodeNumber(value)
        is Char -> JsonPrimitive(value.toString())
        is Enum<*> -> JsonPrimitive(value.name)
        is Path -> JsonPrimitive(value.toString())
        is Duration -> JsonPrimitive(value.toString())
        is Instant, is TemporalAccessor, is URI -> JsonPrimitive(value.toString())
        is DoubleArray -> numbers(value.size, limits) { value[it] }
        is FloatArray -> numbers(value.size, limits) { value[it].toDouble() }
        is IntArray -> numbers(value.size, limits) { value[it].toDouble() }
        is LongArray -> numbers(value.size, limits) { value[it].toDouble() }
        is ByteArray -> buildJsonObject { put("bytes", value.size) }
        is Map<*, *> -> if (depth >= limits.depth) JsonPrimitive("Map(${value.size})") else {
            val shown = value.entries.take(limits.list)
            JsonObject(
                shown.associate { (k, v) -> k.toString() to encode(v, limits, depth + 1) } +
                    (if (value.size > shown.size) mapOf("…" to JsonPrimitive("${value.size - shown.size} de plus")) else emptyMap()),
            )
        }
        is Iterable<*> -> {
            val all = value.toList()
            if (depth >= limits.depth) JsonPrimitive("List(${all.size})")
            else {
                val shown = all.take(limits.list).map { encode(it, limits, depth + 1) }
                JsonArray(if (all.size > shown.size) shown + JsonPrimitive("… ${all.size - shown.size} de plus") else shown)
            }
        }
        is Array<*> -> encode(value.asList(), limits, depth)
        else -> encodeObject(value, limits, depth)
    }

    private fun encodeNumber(n: Number): JsonElement = when (n) {
        is Double -> if (n.isNaN() || n.isInfinite()) JsonPrimitive(n.toString()) else JsonPrimitive(n)
        is Float -> if (n.isNaN() || n.isInfinite()) JsonPrimitive(n.toString()) else JsonPrimitive(n)
        else -> JsonPrimitive(n)
    }

    /** Petits tableaux en entier ; grands tableaux résumés (taille, min, max), NaN = valeur absente. */
    private inline fun numbers(size: Int, limits: Limits, get: (Int) -> Double): JsonElement {
        if (size <= limits.numbers) return JsonArray(List(size) { encodeNumber(get(it)) })
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        var missing = 0
        for (i in 0 until size) {
            val v = get(i)
            if (v.isNaN()) missing++ else { min = minOf(min, v); max = maxOf(max, v) }
        }
        return buildJsonObject {
            put("size", size)
            if (missing < size) { put("min", min); put("max", max) }
            if (missing > 0) put("missing", missing)
        }
    }

    private fun encodeObject(value: Any, limits: Limits, depth: Int): JsonElement {
        val kClass = value::class
        val name = kClass.qualifiedName ?: kClass.java.name
        if (!name.startsWith("dev.highlights.")) return JsonPrimitive(value.toString())
        // Objet sans état (ConfigStatus.Loading…) : son nom suffit.
        if (kClass.objectInstance != null) return JsonPrimitive(kClass.simpleName)
        if (depth >= limits.depth) return JsonPrimitive("${kClass.simpleName}(…)")
        val fields = linkedMapOf<String, JsonElement>()
        // Type concret quand la propriété est déclarée par une interface scellée (ConfigStatus.Ready / Failed).
        if (kClass.supertypes.any { (it.classifier as? KClass<*>)?.isSealed == true }) fields["type"] = JsonPrimitive(kClass.simpleName)
        for (p in properties(kClass)) {
            val v = runCatching { p.getter.call(value) }.getOrElse { continue }
            fields[p.name] = encode(v, limits, depth + 1)
        }
        return JsonObject(fields)
    }

    private fun properties(kClass: KClass<*>) = runCatching {
        // Ordre du constructeur d'abord (celui du code), puis les propriétés calculées.
        val order = kClass.primaryConstructor?.parameters?.mapNotNull { it.name }.orEmpty()
        kClass.memberProperties
            .filter { it.visibility == KVisibility.PUBLIC }
            .sortedBy { order.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ JSON → arguments

    /** Valeur JSON convertie au type d'un paramètre d'action. */
    fun fromJson(json: JsonElement, type: KType): Any? {
        if (json is JsonNull) {
            require(type.isMarkedNullable) { "null refusé pour un paramètre de type $type" }
            return null
        }
        val kClass = type.jvmErasure
        return when {
            kClass == String::class -> json.string()
            kClass == Boolean::class -> (json as? JsonPrimitive)?.booleanOrNull ?: error("booléen attendu : $json")
            kClass == Int::class -> json.number().toInt()
            kClass == Long::class -> json.number().toLong()
            kClass == Double::class -> json.number()
            kClass == Float::class -> json.number().toFloat()
            kClass == Path::class -> Path.of(json.string())
            kClass == URI::class -> URI(json.string())
            kClass == Duration::class -> Duration.parse(json.string())
            kClass.isSubclassOf(Enum::class) -> enumValue(kClass, json.string())
            kClass == List::class || kClass == Collection::class || kClass == Iterable::class ->
                (json as? JsonArray ?: error("liste attendue : $json")).map { fromJson(it, type.arguments.single().type!!) }
            kClass == Set::class -> (json as? JsonArray ?: error("liste attendue : $json")).map { fromJson(it, type.arguments.single().type!!) }.toSet()
            kClass == Function1::class -> patcher(json as? JsonObject ?: error("objet de champs à modifier attendu : $json"), type)
            kClass.isData && json is JsonObject -> construct(kClass, json)
            else -> error("type de paramètre non pris en charge : $type")
        }
    }

    /**
     * Les actions `update*` prennent une fonction (état → nouvel état) : le pont reçoit à la place les champs à changer,
     * `{"fast": true, "maxDurationText": "30s"}`, appliqués par la `copy` de la data class.
     */
    private fun patcher(fields: JsonObject, type: KType): (Any) -> Any {
        val target = type.arguments.first().type ?: error("type de fonction inconnu : $type")
        val kClass = target.jvmErasure
        require(kClass.isData) { "$type : seules les fonctions sur une data class sont pilotables" }
        return { current -> patch(current, fields) }
    }

    /** Copie de [current] avec [fields] remplacés (noms des paramètres du constructeur). */
    fun patch(current: Any, fields: JsonObject): Any {
        val copy = current::class.memberFunctions.first { it.name == "copy" }
        copy.isAccessible = true
        val args = mutableMapOf<KParameter, Any?>(copy.parameters.first() to current)
        val known = copy.parameters.drop(1).associateBy { it.name }
        for ((name, json) in fields) {
            val p = known[name] ?: error("${current::class.simpleName} n'a pas de champ « $name » (champs : ${known.keys.joinToString()})")
            args[p] = fromJson(json, p.type)
        }
        return copy.callBy(args)!!
    }

    private fun construct(kClass: KClass<*>, fields: JsonObject): Any {
        val ctor = kClass.primaryConstructor ?: error("${kClass.simpleName} : pas de constructeur principal")
        val known = ctor.parameters.associateBy { it.name }
        val args = fields.entries.associate { (name, json) ->
            val p = known[name] ?: error("${kClass.simpleName} n'a pas de champ « $name »")
            p to fromJson(json, p.type)
        }
        return ctor.callBy(args)
    }

    private fun enumValue(kClass: KClass<*>, name: String): Any {
        val values = kClass.java.enumConstants
        return values.firstOrNull { (it as Enum<*>).name.equals(name, ignoreCase = true) }
            ?: error("« $name » inconnu pour ${kClass.simpleName} (${values.joinToString { (it as Enum<*>).name }})")
    }

    private fun JsonElement.string(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: (this as? JsonPrimitive)?.contentOrNull ?: error("texte attendu : $this")

    private fun JsonElement.number(): Double = (this as? JsonPrimitive)?.doubleOrNull ?: error("nombre attendu : $this")
}
