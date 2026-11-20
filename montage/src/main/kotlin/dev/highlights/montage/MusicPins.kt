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
 * Un kill posé à la main sur un instant du morceau, gardé pour apprendre : son écart au temps le plus proche
 * ([offsetMs], la durée d'un temps [periodMs]), la force de ce temps ([strength], [MusicAnalysis.strength]) et la force
 * moyenne des temps de son plan ([slotStrength] : ce que le placement automatique aurait pu choisir).
 */
@Serializable
data class PinExample(
    val at: String,
    val profile: String,
    val music: String,
    val capture: String,
    val kill: Long,
    val offsetMs: Long,
    val periodMs: Long,
    val strength: Double,
    val slotStrength: Double,
) {
    val key: String get() = "$profile|$capture|$kill"
}

@Serializable
data class StoredPins(val version: Int = 1, val pins: List<PinExample> = emptyList())

/** Kills posés sur la musique (`<sortie>/sessions/kill-pins.json`), un par kill (le dernier posé remplace). */
class PinHistory(val file: Path) {
    private val lock = Any()

    fun load(): List<PinExample> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            json.decodeFromString(StoredPins.serializer(), file.readText()).pins
        } catch (e: Exception) {
            log.warn { "Kills posés sur la musique illisibles ($file), ignorés : ${e.message}" }
            emptyList()
        }
    }

    fun record(pins: List<PinExample>): Unit = synchronized(lock) {
        if (pins.isEmpty()) return
        val keys = pins.map { it.key }.toSet()
        val next = (load().filter { it.key !in keys } + pins).takeLast(KEEP)
        runCatching {
            file.parent?.createDirectories()
            val tmp = file.resolveSibling(file.name + ".tmp")
            tmp.writeText(json.encodeToString(StoredPins.serializer(), StoredPins(pins = next)))
            tmp.moveTo(file, overwrite = true)
        }.onFailure { log.warn { "Kills posés sur la musique non enregistrés ($file) : ${it.message}" } }
    }

    fun reset(): Path? = synchronized(lock) {
        if (!file.exists()) return null
        val stamp = Instant.now().toString().replace(":", "-").substringBefore('.')
        val aside = file.resolveSibling(file.name.removeSuffix(".json") + ".reset-$stamp.json")
        file.moveTo(aside)
        aside
    }

    private companion object {
        const val KEEP = 2000
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    }
}

/**
 * Ce qui a été appris des kills posés sur la musique : un écart régulier au temps ([offset], « juste avant le temps »)
 * et une préférence pour les temps qui frappent ([accent]). Chacun n'est appliqué ([offsetActive], [accentActive]) que
 * s'il prédit mieux, en validation « une de côté », les kills posés que la règle d'origine.
 */
data class LearnedPins(
    val count: Int = 0,
    val offset: Duration? = null,
    val offsetSpread: Duration? = null,
    val offsetActive: Boolean = false,
    /** Écart moyen absolu « une de côté » avec l'écart appris, et sans (kill sur le temps). */
    val offsetError: Duration? = null,
    val baseError: Duration? = null,
    val accentShare: Double? = null,
    val accentActive: Boolean = false,
) {
    val isEmpty: Boolean get() = count == 0

    /** Décalage des kills par rapport au temps, pour les morceaux sans décalage à eux ; null : aucun. */
    val killShift: Duration? get() = offset?.takeIf { offsetActive }

    /** Poids de la force du temps dans le choix du temps du kill ([dev.highlights.core.model.CutSettings.accentBonus]). */
    val accentBonus: Double get() = if (accentActive) PinLearner.ACCENT_BONUS else 0.0

    companion object {
        val NONE = LearnedPins()
    }
}

/**
 * Apprend où l'utilisateur pose ses kills dans la musique. Règles prudentes comme pour les calages : un nombre minimal
 * d'exemples ([MIN_PINS]), une dispersion maximale, des bornes, et une validation « une de côté » : l'écart appris doit
 * mieux prédire chaque kill posé (appris sans lui) que le kill sur le temps ; la préférence pour les accents doit se
 * vérifier sur [MIN_ACCENT_SHARE] des kills, chacun jugé par la règle apprise sans lui.
 */
object PinLearner {
    const val MIN_PINS = 8

    /** Un écart au temps de plus de 35 % d'un temps vise un contretemps ou une note, pas « le temps, un peu avant ». */
    const val MAX_PHASE = 0.35
    val MAX_SPREAD = 30.milliseconds
    val MIN_OFFSET = 10.milliseconds
    val MAX_OFFSET = 120.milliseconds

    /** Part des kills posés sur un temps plus fort que la moyenne de leur plan, pour croire à une préférence. */
    const val MIN_ACCENT_SHARE = 0.75
    const val MIN_ACCENT_GAIN = 0.1
    const val ACCENT_BONUS = 0.5

    fun learn(pins: List<PinExample>): LearnedPins {
        if (pins.isEmpty()) return LearnedPins.NONE
        val near = pins.filter { abs(it.offsetMs) <= MAX_PHASE * it.periodMs }.map { it.offsetMs }
        fun median(xs: List<Long>) = xs.sorted().let { it[it.size / 2] }
        val offset = near.takeIf { it.isNotEmpty() }?.let(::median)
        val spread = offset?.let { m -> median(near.map { abs(it - m) }) }
        // Une de côté : l'écart appris sans chaque kill, comparé à « sur le temps ».
        val looError = if (near.size >= 2) near.indices.map { i -> abs(near[i] - median(near.filterIndexed { j, _ -> j != i })) }.average() else null
        val baseError = if (near.isNotEmpty()) near.map { abs(it) }.average() else null
        val offsetActive = offset != null && near.size >= MIN_PINS && spread!! <= MAX_SPREAD.inWholeMilliseconds &&
            abs(offset) >= MIN_OFFSET.inWholeMilliseconds && abs(offset) <= MAX_OFFSET.inWholeMilliseconds &&
            looError != null && baseError != null && looError < baseError

        val gains = pins.map { it.strength - it.slotStrength }
        fun prefers(xs: List<Double>) = xs.isNotEmpty() && xs.count { it > 0 }.toDouble() / xs.size >= MIN_ACCENT_SHARE && xs.average() >= MIN_ACCENT_GAIN
        // Une de côté : chaque kill est-il bien prévu par la préférence apprise sans lui ?
        val predicted = gains.indices.count { i -> prefers(gains.filterIndexed { j, _ -> j != i }) && gains[i] > 0 }
        val accentShare = predicted.toDouble() / gains.size
        val accentActive = pins.size >= MIN_PINS && accentShare >= MIN_ACCENT_SHARE
        log.info {
            "Kills posés sur la musique : ${pins.size}, écart ${offset ?: "-"} ms (dispersion ${spread ?: "-"}, une de côté ${looError?.let { "%.0f".format(it) }} contre ${baseError?.let { "%.0f".format(it) }} ms) " +
                "${if (offsetActive) "appliqué" else "pas encore"} ; accents ${"%.2f".format(accentShare)} ${if (accentActive) "appliqués" else "pas encore"}"
        }
        return LearnedPins(
            pins.size, offset?.milliseconds, spread?.milliseconds, offsetActive,
            looError?.toLong()?.milliseconds, baseError?.toLong()?.milliseconds,
            accentShare, accentActive,
        )
    }

    /** Les kills du plan posés sur un instant du morceau, à enregistrer. */
    fun record(plan: MontagePlan, profile: String, now: Instant = Instant.now()): List<PinExample> {
        val music = plan.music
        val key = MusicFeelStore.keyOf(music.file) ?: music.file.name
        return plan.clips.mapNotNull { c ->
            val pin = c.group.pin as? MontagePin.At ?: return@mapNotNull null
            // Le vrai temps le plus proche (pas un temps de coupe ajouté pour un plan court).
            val beat = music.beats.indices.filter { it !in music.virtualBeats }.minByOrNull { (music.beats[it] - pin.music).absoluteValue } ?: 0
            val slotBeats = c.slot.startBeat until c.slot.endBeat
            PinExample(
                now.toString(), profile, key, c.group.media.path.fileName.toString(), c.group.detectedOf(c.anchor).inWholeMilliseconds,
                (pin.music - music.beatTime(beat)).inWholeMilliseconds, music.beatPeriod.inWholeMilliseconds,
                music.strength(beat), slotBeats.map { music.strength(it) }.average().takeIf { !it.isNaN() } ?: 0.0,
            )
        }
    }
}
