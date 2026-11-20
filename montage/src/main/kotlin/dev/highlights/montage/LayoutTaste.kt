package dev.highlights.montage

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * Contexte musical d'un kill posé (étape 1 de « Montage sur la musique ») : écart au vrai temps le plus proche
 * ([offsetMs], un temps dure [periodMs]), sur un contretemps qui frappe ([onHalf]) ou une attaque de note ([onNote]),
 * force du temps ([strength]), section ([section]), après la drop ([afterDrop]), kills de la même mesure ([barKills]).
 * [chosen] : déplacé ou ajouté par l'utilisateur (sinon gardé tel que proposé, ou proposé).
 */
@Serializable
data class KillContext(
    val atMs: Long,
    val offsetMs: Long,
    val periodMs: Long,
    val onHalf: Boolean,
    val onNote: Boolean,
    val strength: Double,
    val section: String,
    val afterDrop: Boolean,
    val barKills: Int,
    val chosen: Boolean = false,
)

/**
 * Une validation de l'étape 1 (« Placer les plans ») : la proposition de l'algorithme ([auto]) et le placement final
 * ([final]), avec ce qui a changé ([moved] déplacés, [added] ajoutés, [removed] retirés).
 */
@Serializable
data class LayoutExample(
    val at: String,
    val profile: String,
    val music: String,
    val auto: List<KillContext>,
    val final: List<KillContext>,
    val moved: Int,
    val added: Int,
    val removed: Int,
    /** Repères de l'étape 1 autres que les kills (contexte musical de chacun) et la vitrine (début, fin en ms). */
    val cuts: List<KillContext> = emptyList(),
    val slows: List<KillContext> = emptyList(),
    val transitions: List<KillContext> = emptyList(),
    val showcase: List<Long>? = null,
)

@Serializable
data class StoredLayouts(val version: Int = 1, val examples: List<LayoutExample> = emptyList())

/** Validations de l'étape 1 (`<sortie>/sessions/kill-layout.json`), dans l'ordre. */
class LayoutHistory(val file: Path) {
    private val lock = Any()

    fun load(): List<LayoutExample> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            json.decodeFromString(StoredLayouts.serializer(), file.readText()).examples
        } catch (e: Exception) {
            log.warn { "Placements de kills illisibles ($file), ignorés : ${e.message}" }
            emptyList()
        }
    }

    fun record(example: LayoutExample): Unit = synchronized(lock) {
        val next = (load() + example).takeLast(KEEP)
        runCatching {
            file.parent?.createDirectories()
            val tmp = file.resolveSibling(file.name + ".tmp")
            tmp.writeText(json.encodeToString(StoredLayouts.serializer(), StoredLayouts(examples = next)))
            tmp.moveTo(file, overwrite = true)
        }.onFailure { log.warn { "Placement de kills non enregistré ($file) : ${it.message}" } }
    }

    fun reset(): Path? = synchronized(lock) {
        if (!file.exists()) return null
        val stamp = Instant.now().toString().replace(":", "-").substringBefore('.')
        val aside = file.resolveSibling(file.name.removeSuffix(".json") + ".reset-$stamp.json")
        file.moveTo(aside)
        aside
    }

    private companion object {
        const val KEEP = 500
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    }
}

/**
 * Ce qui a été appris des placements de l'étape 1, chacun appliqué seulement s'il est actif :
 * - [offset] : écart préféré au temps (« ~20 ms après le temps ») ;
 * - [accentGain] : les kills choisis tombent sur des temps plus forts que ceux proposés ;
 * - [density] : kills par mesure du placement final / de la proposition (plus serré > 1) ;
 * - [offbeatShare], [afterDropShare] (et ceux de la proposition) : montrés, pas appliqués.
 */
data class LearnedLayout(
    val montages: Int = 0,
    val chosen: Int = 0,
    val offset: Duration? = null,
    val offsetSpread: Duration? = null,
    val offsetActive: Boolean = false,
    val accentGain: Double? = null,
    val accentActive: Boolean = false,
    val density: Double? = null,
    val densityActive: Boolean = false,
    val offbeatShare: Double? = null,
    val autoOffbeatShare: Double? = null,
    val afterDropShare: Double? = null,
    val autoAfterDropShare: Double? = null,
) {
    val isEmpty: Boolean get() = montages == 0

    /** Décalage des kills par rapport au temps, pour les morceaux sans décalage à eux ; null : aucun. */
    val killShift: Duration? get() = offset?.takeIf { offsetActive }

    val accentBonus: Double get() = if (accentActive) LayoutTasteLearner.ACCENT_BONUS else 0.0

    /** Facteur sur la durée visée par kill (`MontageLength.perClip`) : plus serré, plans plus courts. */
    val perClipFactor: Double get() = density?.takeIf { densityActive }?.let { 1 / it } ?: 1.0

    companion object {
        val NONE = LearnedLayout()
    }
}

/**
 * Apprend des validations de l'étape 1, avec les règles prudentes des autres apprentissages : au moins [MIN_MONTAGES]
 * validations, une dispersion maximale, des bornes, et une validation « une de côté » par montage (appris sans un
 * montage, prédit-il ce montage-là ?). Jamais appliqué par-dessus un placement fait à la main : il ne change que la
 * proposition automatique.
 */
object LayoutTasteLearner {
    const val MIN_MONTAGES = 3
    const val MIN_CHOSEN = 8
    const val MAX_PHASE = 0.35
    val MAX_SPREAD = 30.milliseconds
    val MIN_OFFSET = 10.milliseconds
    val MAX_OFFSET = 120.milliseconds
    const val MIN_ACCENT_GAIN = 0.1
    const val ACCENT_BONUS = 0.5
    const val MIN_AGREEMENT = 0.75

    /** Densité : écart à 1 au-delà duquel une préférence compte, bornes du facteur appliqué. */
    const val DENSITY_MARGIN = 0.1
    const val MIN_DENSITY = 0.7
    const val MAX_DENSITY = 1.5

    fun learn(examples: List<LayoutExample>): LearnedLayout {
        if (examples.isEmpty()) return LearnedLayout.NONE
        fun median(xs: List<Double>) = xs.sorted().let { it[it.size / 2] }
        val enough = examples.size >= MIN_MONTAGES

        // (1) Écart au temps des kills que l'utilisateur a placés lui-même.
        fun offsets(e: LayoutExample) = e.final.filter { it.chosen && abs(it.offsetMs) <= MAX_PHASE * it.periodMs }.map { it.offsetMs.toDouble() }
        val all = examples.flatMap(::offsets)
        val offset = all.takeIf { it.isNotEmpty() }?.let(::median)
        val spread = offset?.let { m -> median(all.map { abs(it - m) }) }
        val looBetter = examples.indices.filter { offsets(examples[it]).isNotEmpty() }.let { idx ->
            val others = idx.map { i -> examples.filterIndexed { j, _ -> j != i }.flatMap(::offsets) }
            if (idx.size < 2 || others.any { it.isEmpty() }) false
            else {
                val model = idx.zip(others).sumOf { (i, o) -> offsets(examples[i]).sumOf { abs(it - median(o)) } }
                val base = idx.sumOf { i -> offsets(examples[i]).sumOf { abs(it) } }
                model < base
            }
        }
        val offsetActive = enough && offset != null && all.size >= MIN_CHOSEN && spread!! <= MAX_SPREAD.inWholeMilliseconds &&
            abs(offset) >= MIN_OFFSET.inWholeMilliseconds && abs(offset) <= MAX_OFFSET.inWholeMilliseconds && looBetter

        // (2) Moments préférés : les kills choisis frappent-ils plus fort que ceux proposés ?
        fun gain(e: LayoutExample): Double? {
            val chosen = e.final.filter { it.chosen }.takeIf { it.isNotEmpty() } ?: return null
            if (e.auto.isEmpty()) return null
            return chosen.map { it.strength }.average() - e.auto.map { it.strength }.average()
        }
        val gains = examples.mapNotNull(::gain)
        val accentGain = gains.takeIf { it.isNotEmpty() }?.let(::median)
        val accentAgree = gains.indices.count { i ->
            val others = gains.filterIndexed { j, _ -> j != i }
            others.isNotEmpty() && median(others) >= MIN_ACCENT_GAIN && gains[i] > 0
        }
        val accentActive = enough && gains.size >= MIN_MONTAGES && accentGain != null && accentGain >= MIN_ACCENT_GAIN &&
            accentAgree.toDouble() / gains.size >= MIN_AGREEMENT

        // (3) Densité : kills par mesure du placement final contre la proposition.
        fun perBar(list: List<KillContext>): Double? {
            if (list.size < 2) return null
            val span = (list.maxOf { it.atMs } - list.minOf { it.atMs }).toDouble()
            val bar = 4.0 * list.first().periodMs
            return list.size / (span / bar + 1)
        }
        val ratios = examples.mapNotNull { e -> perBar(e.final)?.let { f -> perBar(e.auto)?.let { a -> f / a } } }
        val density = ratios.takeIf { it.isNotEmpty() }?.let(::median)?.coerceIn(MIN_DENSITY, MAX_DENSITY)
        fun side(x: Double) = when {
            x > 1 + DENSITY_MARGIN -> 1
            x < 1 - DENSITY_MARGIN -> -1
            else -> 0
        }
        val densityAgree = ratios.indices.count { i ->
            val others = ratios.filterIndexed { j, _ -> j != i }
            others.isNotEmpty() && side(median(others)) != 0 && side(median(others)) == side(ratios[i])
        }
        val densityActive = enough && ratios.size >= MIN_MONTAGES && density != null && side(density) != 0 &&
            densityAgree.toDouble() / ratios.size >= MIN_AGREEMENT

        // Montrés seulement : contretemps, après la drop.
        val chosenAll = examples.flatMap { e -> e.final.filter { it.chosen } }
        val autoAll = examples.flatMap { it.auto }
        fun share(list: List<KillContext>, p: (KillContext) -> Boolean) = list.takeIf { it.isNotEmpty() }?.let { l -> l.count(p).toDouble() / l.size }
        val learned = LearnedLayout(
            examples.size, chosenAll.size, offset?.toLong()?.milliseconds, spread?.toLong()?.milliseconds, offsetActive,
            accentGain, accentActive, density, densityActive,
            share(chosenAll) { it.onHalf }, share(autoAll) { it.onHalf }, share(chosenAll) { it.afterDrop }, share(autoAll) { it.afterDrop },
        )
        log.info { "Placements appris : ${examples.size} montage(s), ${chosenAll.size} kill(s) choisis ; écart ${learned.offset} ${if (offsetActive) "appliqué" else "-"}, accents ${accentGain?.let { "%+.2f".format(it) }} ${if (accentActive) "appliqués" else "-"}, densité ${density?.let { "%.2f".format(it) }} ${if (densityActive) "appliquée" else "-"}" }
        return learned
    }

    /**
     * Contexte musical des kills posés [kills] (instants du morceau) sur [music] ; [chosen] : ceux que l'utilisateur
     * a placés lui-même. Les temps de coupe ajoutés pour des plans courts ne comptent pas comme des temps.
     */
    fun contexts(music: MusicAnalysis, kills: List<Duration>, chosen: Set<Duration> = emptySet()): List<KillContext> {
        val real = music.beats.indices.filter { it !in music.virtualBeats }
        if (real.isEmpty()) return emptyList()
        val period = music.beatPeriod
        val hits = music.hits(0, music.beats.lastIndex)
        val drop = music.beatTime(music.dropBeat)
        return kills.sorted().map { at ->
            val b = real.minBy { (music.beats[it] - at).absoluteValue }
            val bar = (b - music.downbeatPhase).floorDiv(4)
            val barKills = kills.count { k -> (real.minBy { (music.beats[it] - k).absoluteValue } - music.downbeatPhase).floorDiv(4) == bar }
            val half = b.takeIf { it < music.beats.lastIndex }?.let { music.isHalfHit(it) && (music.halfTime(it) - at).absoluteValue <= NEAR } == true ||
                (b > 0 && music.isHalfHit(b - 1) && (music.halfTime(b - 1) - at).absoluteValue <= NEAR)
            KillContext(
                at.inWholeMilliseconds, (at - music.beats[b]).inWholeMilliseconds, period.inWholeMilliseconds,
                onHalf = half, onNote = hits.any { (it.at - at).absoluteValue <= NEAR && it.strength >= NOTE_STRENGTH },
                strength = music.strength(b), section = music.sectionAt(b).kind.name.lowercase(), afterDrop = at >= drop,
                barKills = barKills, chosen = at in chosen,
            )
        }
    }

    /** Proche d'une frappe : à 30 ms près. */
    private val NEAR = 30.milliseconds
    private const val NOTE_STRENGTH = 0.5
}
