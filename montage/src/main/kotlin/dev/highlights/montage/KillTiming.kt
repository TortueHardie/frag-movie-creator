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

/** Kill déplacé après l'inspection : instant que la détection donnait ([detected]), et déplacé à la main ou appris. */
data class KillRetime(val detected: Duration, val byHand: Boolean)

/**
 * Une correction de calage faite dans la frise et rendue : le kill [kill] (instant détecté, en ms, dans [capture]) dont
 * la détection venait de [source] ([KillTimingLearner.sourceOf]), avancé ou retardé de [shiftMs] dans la capture ; et/ou
 * posé à [beatShiftMs] de son temps [beat] dans la musique [music] ([MusicFeelStore.keyOf] : nom et taille). Une seule
 * correction par kill : la dernière rendue remplace les précédentes (un même calage rendu deux fois ne compte qu'une).
 */
@Serializable
data class TimingCorrection(
    val at: String,
    val profile: String,
    val capture: String,
    val kill: Long,
    val source: String? = null,
    val shiftMs: Long? = null,
    val music: String? = null,
    val beat: Int? = null,
    val beatShiftMs: Long? = null,
) {
    val key: String get() = "${capture.lowercase()}@$kill"
}

/** Fichier `kill-timing.json` : version du format et corrections, de la plus ancienne à la plus récente. */
@Serializable
data class StoredTiming(val version: Int = 1, val corrections: List<TimingCorrection> = emptyList())

/**
 * Historique des calages (`outputDir/sessions/kill-timing.json`, à côté de `taste-history.json`) : alimenté à chaque
 * montage rendu avec des kills calés dans la frise, lu par [KillTimingLearner] et par la vue des goûts.
 */
class TimingHistory(val file: Path) {
    private val lock = Any()

    fun load(): StoredTiming = synchronized(lock) {
        if (!file.exists()) return StoredTiming()
        try {
            json.decodeFromString(StoredTiming.serializer(), file.readText())
        } catch (e: Exception) {
            log.warn { "Historique des calages illisible ($file), ignoré : ${e.message}" }
            StoredTiming()
        }
    }

    /** Ajoute [corrections] ; une correction d'un kill déjà corrigé remplace l'ancienne. */
    fun record(corrections: List<TimingCorrection>): Unit = synchronized(lock) {
        if (corrections.isEmpty()) return
        val keys = corrections.map { it.key }.toSet()
        val next = load().let { s -> s.copy(corrections = (s.corrections.filter { it.key !in keys } + corrections).takeLast(KEEP)) }
        runCatching {
            file.parent?.createDirectories()
            val tmp = file.resolveSibling(file.name + ".tmp")
            tmp.writeText(json.encodeToString(StoredTiming.serializer(), next))
            tmp.moveTo(file, overwrite = true)
        }.onFailure { log.warn { "Historique des calages non enregistré ($file) : ${it.message}" } }
        Unit
    }

    /** Oublie les calages appris : le fichier est mis de côté (`kill-timing.reset-<instant>.json`), comme [TasteHistory.reset]. */
    fun reset(): Path? = synchronized(lock) {
        if (!file.exists()) return null
        val stamp = Instant.now().toString().replace(":", "-").substringBefore('.')
        val aside = file.resolveSibling(file.name.removeSuffix(".json") + ".reset-$stamp.json")
        file.moveTo(aside)
        aside
    }

    companion object {
        const val KEEP = 2000
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    }
}

/**
 * Décalage appris : [offset] (médiane des corrections, bornée), [count] corrections, [spread] leur écart à la médiane
 * (médiane des écarts absolus) ; [active] : assez de corrections, assez concordantes et assez loin de zéro pour servir.
 */
data class LearnedOffset(val key: String, val offset: Duration, val count: Int, val spread: Duration, val active: Boolean)

/**
 * Ce que les calages ont appris : [sources], le retard ou l'avance systématique d'une détection par profil (clé
 * « profil|source ») ; [songs], le décalage des kills par rapport aux temps d'un morceau (clé de [MusicFeelStore.keyOf]).
 */
data class LearnedTiming(val sources: List<LearnedOffset> = emptyList(), val songs: List<LearnedOffset> = emptyList(), val corrections: Int = 0) {
    fun sourceOffset(profile: String, source: String?): Duration? =
        source?.let { s -> sources.firstOrNull { it.active && it.key == KillTimingLearner.sourceKey(profile, s) }?.offset }

    fun songOffset(music: String?): Duration? = music?.let { m -> songs.firstOrNull { it.active && it.key == m }?.offset }

    val isEmpty: Boolean get() = sources.isEmpty() && songs.isEmpty()

    companion object {
        val NONE = LearnedTiming()
    }
}

/**
 * Apprend des calages faits à la main. Deux erreurs se corrigent pour la suite :
 * - une détection systématiquement en retard ou en avance (ex. les kills Outplayed de VALORANT, annoncés après la balle) :
 *   par profil × source de la détection, appliqué aux kills de cette source qu'on n'a pas calés à la main ;
 * - un morceau dont les temps détectés sont décalés : le décalage choisi sur ses plans, réutilisé sur tous ses plans.
 * Prudence des autres apprentissages ([GroupTasteLearner], [MusicFeelLearner]) : rien sous un nombre de corrections,
 * rien si elles se contredisent (écart trop grand), rien de minuscule, et un décalage borné.
 */
object KillTimingLearner {
    /**
     * Corrections d'une même source avant d'en tirer un décalage : un calage isolé peut corriger un kill mal lu, pas la
     * source. 8 (5 au départ) : l'utilisateur a voulu plus de prudence (20/11).
     */
    const val MIN_SOURCE = 8

    /** Écart toléré entre les corrections d'une source (≈ 2 images à 60 img/s) : au-delà, elles ne disent pas un retard constant. */
    val MAX_SOURCE_SPREAD = 40.milliseconds

    /** Décalage de source au plus : au-delà, c'est la détection qu'il faut corriger, pas compenser. */
    val MAX_SOURCE_OFFSET = 200.milliseconds

    /** Plans calés sur un même morceau avant d'en décaler tous les plans (3 ; 2 au départ, jugé trop rapide le 20/11). */
    const val MIN_SONG = 3

    val MAX_SONG_SPREAD = 30.milliseconds

    val MAX_SONG_OFFSET = 120.milliseconds

    /** Décalage en deçà duquel on ne change rien (moins d'une image à 60 img/s) : du bruit de clic. */
    val MIN_OFFSET = 10.milliseconds

    fun sourceKey(profile: String, source: String) = "$profile|$source"

    /**
     * Source de l'instant d'un kill : son détecteur et ce qui l'a recalé, avec le recalage sur le son du tir
     * ([KillInspector]) que les particularités ne disent qu'après l'inspection ; « …/event » : l'instant annoncé tel quel.
     */
    fun sourceOf(traits: KillTraits): String? {
        val base = traits.source ?: return null
        if ('/' in base) return base
        return if (traits.fatal == null && traits.shift != Duration.ZERO) "$base/shot-sound" else "$base/event"
    }

    fun learn(corrections: List<TimingCorrection>): LearnedTiming {
        val sources = corrections.filter { it.shiftMs != null && it.source != null }.groupBy { sourceKey(it.profile, it.source!!) }
            .map { (key, list) -> offset(key, list.map { it.shiftMs!! }, MIN_SOURCE, MAX_SOURCE_SPREAD, MAX_SOURCE_OFFSET) }
        val songs = corrections.filter { it.beatShiftMs != null && it.music != null }.groupBy { it.music!! }
            .map { (key, list) -> offset(key, list.map { it.beatShiftMs!! }, MIN_SONG, MAX_SONG_SPREAD, MAX_SONG_OFFSET) }
        return LearnedTiming(sources.sortedBy { it.key }, songs.sortedBy { it.key }, corrections.size)
    }

    private fun offset(key: String, values: List<Long>, min: Int, maxSpread: Duration, max: Duration): LearnedOffset {
        val median = median(values.map { it.toDouble() })
        val spread = median(values.map { abs(it - median) })
        val offset = median.milliseconds.coerceIn(-max, max)
        val active = values.size >= min && spread.milliseconds <= maxSpread && offset.absoluteValue >= MIN_OFFSET
        return LearnedOffset(key, offset, values.size, spread.milliseconds, active)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * Décale les kills dont la source a un décalage appris, sauf ceux calés à la main (et les instants déjà déplacés).
     * Le kill garde sa désignation (instant détecté, [KillGroup.retimed]).
     */
    fun applyTo(groups: List<KillGroup>, learned: LearnedTiming, profile: String): List<KillGroup> {
        if (learned.sources.none { it.active }) return groups
        return groups.map { g ->
            if (g.traits.isEmpty()) return@map g
            val moved = g.kills.mapIndexed { i, k ->
                val shift = if (g.retimed.containsKey(k)) null else learned.sourceOffset(profile, sourceOf(g.traits[i]))
                Triple(shift?.let { (k + it).coerceIn(g.media.bounds.start, g.media.bounds.end) } ?: k, g.traits[i], k to (shift != null))
            }
            if (moved.none { it.third.second }) return@map g
            val sorted = moved.sortedBy { it.first }
            val kept = sorted.map { it.first }.toSet()
            g.copy(
                kills = sorted.map { it.first },
                traits = sorted.map { it.second },
                retimed = (g.retimed + sorted.filter { it.third.second }.associate { it.first to KillRetime(it.third.first, byHand = false) }).filterKeys { it in kept },
            )
        }
    }

    /**
     * Corrections de calage d'un montage rendu : chaque kill calé à la main ([MontageConstraints.anchors]) avec la source
     * de sa détection, lue dans [candidates] (groupes d'avant les corrections), et chaque décalage du temps avec le temps
     * du plan qui le porte dans [plan]. [musicKey] : identité de la musique ([MusicFeelStore.keyOf]).
     */
    fun record(
        candidates: List<KillGroup>,
        plan: MontagePlan,
        constraints: MontageConstraints,
        profile: String,
        musicKey: String?,
        now: String,
    ): List<TimingCorrection> {
        val byKill = linkedMapOf<String, TimingCorrection>()
        for ((ref, at) in constraints.anchors) {
            val (path, kill) = MontageEdits.designated(ref, candidates) ?: continue
            val group = candidates.first { it.media.path == path && kill in it.kills }
            val c = TimingCorrection(now, profile, path.fileName.toString(), kill.inWholeMilliseconds, sourceOf(group.traitsOf(kill)), (at - kill).inWholeMilliseconds)
            byKill[c.key] = c
        }
        for ((ref, shift) in constraints.beatShifts) {
            val (path, kill) = MontageEdits.designated(ref, candidates) ?: continue
            val clip = plan.clips.firstOrNull { c -> c.group.media.path == path && c.group.kills.any { (c.group.detectedOf(it) - kill).absoluteValue < 2.milliseconds } } ?: continue
            val anchor = clip.group.detectedOf(clip.anchor)
            val group = candidates.firstOrNull { it.media.path == path && anchor in it.kills }
            val key = TimingCorrection(now, profile, path.fileName.toString(), anchor.inWholeMilliseconds).key
            val base = byKill[key] ?: TimingCorrection(now, profile, path.fileName.toString(), anchor.inWholeMilliseconds, group?.let { sourceOf(it.traitsOf(anchor)) })
            byKill[key] = base.copy(music = musicKey, beat = clip.anchorBeat, beatShiftMs = shift.inWholeMilliseconds)
        }
        return byKill.values.toList()
    }
}
