package dev.highlights.montage

import dev.highlights.core.InputException
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.TimeRange
import dev.highlights.core.serialization.Durations
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
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * « Couper ici » : début ([start]) et fin ([end]) du plan d'un kill dans sa capture, posés à la main ; null : au choix
 * du planificateur. Le plan garde sa longueur en sortie : la partie d'avant ou d'après le kill est ralentie ou
 * accélérée pour remplir sa place ([ClipMarkers.fit]), jamais coupée en silence.
 */
data class ClipCut(val start: Duration? = null, val end: Duration? = null) {
    val isEmpty: Boolean get() = start == null && end == null

    /** Les bornes de [other] remplacent celles-ci quand elles sont posées. */
    fun with(other: ClipCut) = ClipCut(other.start ?: start, other.end ?: end)
}

/** Sorte d'une section de capture marquée à la main. */
enum class RangeKind {
    /** « Section sans kill » : jamais montrée ; un kill détecté dedans est écarté. */
    SKIP,

    /** « Section à garder entière » : un plan qui en montre une partie la montre toute (pas de coupe dedans). */
    KEEP,
}

/** Section [range] de la capture [capture] (nom du fichier), de sorte [kind]. Texte : « partie.mp4@1:02.5..1:10 ». */
data class CaptureRange(val capture: String, val range: TimeRange, val kind: RangeKind) {
    fun matches(media: MediaInfo): Boolean = media.path.fileName.toString().equals(capture, ignoreCase = true)

    override fun toString() = "$capture@${KillRef.precise(range.start)}..${KillRef.precise(range.end)}"

    companion object {
        /** « capture@début..fin » (instants comme [KillRef.parse]). */
        fun parse(text: String, kind: RangeKind): CaptureRange {
            val at = text.lastIndexOf('@')
            val parts = if (at > 0) text.substring(at + 1).split("..") else emptyList()
            val from = parts.getOrNull(0)?.let(Durations::parseOrNull)
            val to = parts.getOrNull(1)?.let(Durations::parseOrNull)
            if (at <= 0 || parts.size != 2 || from == null || to == null || to <= from) {
                throw InputException(Message("error.montage.edit.badRange", "text" to text))
            }
            return CaptureRange(text.substring(0, at).trim(), TimeRange(from, to), kind)
        }
    }
}

/**
 * Les repères d'un plan appliqués à sa source : coupes à la main ([KillGroup.cut]), sections sans kill
 * ([KillGroup.skips]) et à garder entières ([KillGroup.keeps]).
 */
object ClipMarkers {
    /** Ralenti le plus fort qu'une coupe peut demander (vitesse ×0,5) ; au-delà, la zone est trop courte pour le plan. */
    const val MIN_FACTOR = 0.5

    /** Accéléré le plus fort (×2) qu'une section à garder peut demander ; au-delà, elle est trop longue pour le plan. */
    const val MAX_FACTOR = 2.0

    /**
     * Le plan [c] ajusté à ses repères. La partie d'avant le kill (jusqu'au ralenti du kill) et celle d'après (depuis
     * la fin du ralenti) sont prises entre les nouvelles bornes et jouées à la vitesse qui remplit exactement leur place
     * en sortie : le kill reste sur son temps, le plan garde sa longueur. Une vitesse hors de [MIN_FACTOR]..[MAX_FACTOR]
     * (zone trop courte ou section trop longue pour cette place) laisse le plan tel quel avec [MontageClip.markerMiss] :
     * le planificateur essaie d'autres places, et si le plan retenu le garde, [MontageEdits.check] arrête tout avec ce
     * message (pas de coupe silencieuse).
     */
    fun fit(c: MontageClip): MontageClip {
        val g = c.group
        val cut = g.cut
        if ((cut == null || cut.isEmpty) && g.skips.isEmpty() && g.keeps.isEmpty()) return c
        val a = c.anchor
        val held = c.speeds.filter { it.kind == SpeedKind.SLOW || it.kind == SpeedKind.FREEZE }
        val p0 = held.filter { it.range.start <= a }.minOfOrNull { it.range.start } ?: a
        val p1 = held.filter { it.range.end >= a }.maxOfOrNull { it.range.end } ?: a
        var s = cut?.start ?: c.start
        var e = cut?.end ?: c.end
        // Section sans kill : jamais montrée. Avant le kill, le plan commence après elle ; après, il finit avant.
        for (r in g.skips) {
            if (r.start < p1 && r.end > p0) return c.copy(markerMiss = Message("error.montage.edit.skipOnKill", "kill" to MontageEdits.label(g), "range" to rangeText(r)))
            if (r.end <= p0 && r.end > s) s = r.end
            if (r.start >= p1 && r.start < e) e = r.start
        }
        // Section à garder entière : une borne qui tomberait dedans va à son bord (sauf coupe posée à la main).
        for (r in g.keeps) {
            if (cut?.start == null && s > r.start && s < r.end && r.start < p0) s = r.start
            if (cut?.end == null && e > r.start && e < r.end && r.end > p1) e = r.end
        }
        s = s.coerceAtLeast(g.media.bounds.start)
        e = e.coerceAtMost(g.media.duration)
        val preChanged = s != c.start
        val postChanged = e != c.end
        if (!preChanged && !postChanged) return c
        if (s >= p0 || e <= p1) return c.copy(markerMiss = Message("error.montage.edit.cutAroundKill", "kill" to MontageEdits.label(g)))
        val middle = c.speeds.filter { it.range.start >= p0 && it.range.end <= p1 }
        val pre = if (preChanged) retime(s, p0, c.toOutput(p0), g, byHand = s == cut?.start).getOrElse { return c.copy(markerMiss = it) } else c.speeds.filter { it.range.end <= p0 }
        val post = if (postChanged) retime(p1, e, c.outputLength - c.toOutput(p1), g, byHand = e == cut?.end).getOrElse { return c.copy(markerMiss = it) } else c.speeds.filter { it.range.start >= p1 }
        log.info { "Repères sur ${MontageEdits.label(g)} : source ${Durations.format(c.start)}-${Durations.format(c.end)} -> ${Durations.format(s)}-${Durations.format(e)}" }
        return c.copy(
            start = s, end = e,
            padBefore = if (preChanged) Duration.ZERO else c.padBefore,
            padAfter = if (postChanged) Duration.ZERO else c.padAfter,
            speeds = (pre + middle + post).sortedBy { it.range.start },
        )
    }

    /** La source [from]..[to] jouée sur [out] de sortie : une vitesse, ou rien si c'est la vitesse normale ; sinon pourquoi c'est impossible. */
    private sealed interface Retimed {
        data class Done(val speeds: List<SpeedSegment>) : Retimed
        data class Missed(val why: Message) : Retimed
    }

    private inline fun Retimed.getOrElse(onMiss: (Message) -> List<SpeedSegment>): List<SpeedSegment> = when (this) {
        is Retimed.Done -> speeds
        is Retimed.Missed -> onMiss(why)
    }

    /** [byHand] : la borne vient d'une coupe posée à la main (sinon d'une section) ; le message le dit. */
    private fun retime(from: Duration, to: Duration, out: Duration, g: KillGroup, byHand: Boolean): Retimed {
        if (!out.isPositive()) return Retimed.Missed(Message("error.montage.edit.cutAroundKill", "kill" to MontageEdits.label(g)))
        val factor = (to - from) / out
        if (factor < MIN_FACTOR - 1e-9) {
            val key = if (byHand) "error.montage.edit.cutTooShort" else "error.montage.edit.skipTooShort"
            return Retimed.Missed(Message(key, "kill" to MontageEdits.label(g), "needed" to Durations.format(out * MIN_FACTOR), "given" to Durations.format(to - from)))
        }
        if (factor > MAX_FACTOR + 1e-9) {
            val key = if (byHand) "error.montage.edit.cutTooLong" else "error.montage.edit.keepTooLong"
            return Retimed.Missed(Message(key, "kill" to MontageEdits.label(g), "room" to Durations.format(out * MAX_FACTOR), "given" to Durations.format(to - from)))
        }
        return Retimed.Done(if (abs(factor - 1.0) < 1e-3) emptyList() else listOf(SpeedSegment(TimeRange(from, to), factor, SpeedKind.RAMP)))
    }

    private fun rangeText(r: TimeRange) = "${KillRef.precise(r.start)}..${KillRef.precise(r.end)}"
}

/** Une coupe posée à la main, gardée pour apprendre : profil, et longueur de source avant et après le kill (ms). */
@Serializable
data class CutPreference(val at: String, val profile: String, val capture: String, val kill: Long, val beforeMs: Long? = null, val afterMs: Long? = null) {
    val key: String get() = "$profile|$capture|$kill"
}

@Serializable
data class StoredCuts(val version: Int = 1, val cuts: List<CutPreference> = emptyList())

/** Coupes posées à la main (`<sortie>/sessions/kill-cuts.json`), une par kill (la dernière remplace). */
class CutHistory(val file: Path) {
    private val lock = Any()

    fun load(): List<CutPreference> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            json.decodeFromString(StoredCuts.serializer(), file.readText()).cuts
        } catch (e: Exception) {
            log.warn { "Coupes illisibles ($file), ignorées : ${e.message}" }
            emptyList()
        }
    }

    fun record(cuts: List<CutPreference>): Unit = synchronized(lock) {
        if (cuts.isEmpty()) return
        val keys = cuts.map { it.key }.toSet()
        save(load().filter { it.key !in keys } + cuts)
    }

    fun reset(): Path? = synchronized(lock) {
        if (!file.exists()) return null
        val stamp = Instant.now().toString().replace(":", "-").substringBefore('.')
        val aside = file.resolveSibling(file.name.removeSuffix(".json") + ".reset-$stamp.json")
        file.moveTo(aside)
        aside
    }

    private fun save(cuts: List<CutPreference>) {
        runCatching {
            file.parent?.createDirectories()
            val tmp = file.resolveSibling(file.name + ".tmp")
            tmp.writeText(json.encodeToString(StoredCuts.serializer(), StoredCuts(cuts = cuts.takeLast(KEEP))))
            tmp.moveTo(file, overwrite = true)
        }.onFailure { log.warn { "Coupes non enregistrées ($file) : ${it.message}" } }
    }

    private companion object {
        const val KEEP = 2000
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    }
}

/** Longueur préférée d'un côté du kill, apprise des coupes d'un profil. */
data class LearnedLength(val length: Duration, val count: Int, val spread: Duration, val active: Boolean)

/** Longueurs préférées avant et après le kill, par profil ([ClipLengthLearner]). */
data class LearnedLengths(val before: Map<String, LearnedLength> = emptyMap(), val after: Map<String, LearnedLength> = emptyMap()) {
    val isEmpty: Boolean get() = before.isEmpty() && after.isEmpty()

    fun beforeFor(profile: String) = before[profile]?.takeIf { it.active }?.length
    fun afterFor(profile: String) = after[profile]?.takeIf { it.active }?.length

    companion object {
        val NONE = LearnedLengths()
    }
}

/**
 * Longueur de plan que l'utilisateur préfère avant et après le kill, apprise de ses coupes, par profil. Mêmes règles
 * prudentes que les calages : un nombre minimal de coupes concordantes ([MIN_CUTS]), une dispersion maximale
 * ([MAX_SPREAD], écart médian absolu), des bornes. Elle oriente seulement le choix du temps du kill dans son plan
 * ([dev.highlights.core.model.CutSettings.preferredLead]) ; une coupe posée à la main passe toujours devant.
 */
object ClipLengthLearner {
    const val MIN_CUTS = 8
    val MAX_SPREAD = 300.milliseconds
    val MIN_LENGTH = 0.2.seconds
    val MAX_LENGTH = 4.seconds

    fun learn(cuts: List<CutPreference>): LearnedLengths {
        fun side(values: (CutPreference) -> Long?) = cuts.groupBy { it.profile }.mapNotNull { (profile, list) ->
            val ms = list.mapNotNull(values).sorted()
            if (ms.isEmpty()) return@mapNotNull null
            val median = ms[ms.size / 2]
            val spread = ms.map { abs(it - median) }.sorted()[ms.size / 2]
            val length = median.milliseconds.coerceIn(MIN_LENGTH, MAX_LENGTH)
            profile to LearnedLength(length, ms.size, spread.milliseconds, ms.size >= MIN_CUTS && spread.milliseconds <= MAX_SPREAD)
        }.toMap()
        return LearnedLengths(side { it.beforeMs }, side { it.afterMs })
    }

    /** Coupes du plan [plan] posées à la main, à enregistrer (profil [profile]). */
    fun record(plan: MontagePlan, profile: String, now: Instant = Instant.now()): List<CutPreference> = plan.clips.mapNotNull { c ->
        val cut = c.group.cut?.takeIf { !it.isEmpty } ?: return@mapNotNull null
        CutPreference(
            now.toString(), profile, c.group.media.path.fileName.toString(), c.group.detectedOf(c.anchor).inWholeMilliseconds,
            cut.start?.let { (c.anchor - it).inWholeMilliseconds }, cut.end?.let { (it - c.anchor).inWholeMilliseconds },
        )
    }
}
