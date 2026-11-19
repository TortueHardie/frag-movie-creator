package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.ReactionMontage
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.TimelineSegment
import dev.highlights.core.serialization.Durations
import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.session.Session
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/** Nature d'une réaction : reconnue par YAMNet (rire, cri), sinon un éclat de voix. */
enum class ReactionKind(val segment: String) { LAUGH("laughter"), SHOUT("shout"), VOICE("shout") }

/**
 * Une réaction du joueur au micro. [range] : l'éclat (ou le rire) ; [peak] : son niveau le plus fort ; [rise] : de
 * combien (dB) il dépasse la voix habituelle ; [cause] : type de l'événement du jeu qui la précède (kill, mort…), s'il y
 * en a un dans `causeWindow` ; [strength] : force pour le classement (voir [ReactionFinder.strength]).
 */
@Serializable
data class Reaction(
    val range: TimeRange,
    val peak: SerialDuration,
    val rise: Double,
    val kind: ReactionKind,
    val cause: String? = null,
    val strength: Double = 0.0,
)

/** Niveau du micro par tranche de [step] (dBFS, RMS). */
class MicLevels(val step: Duration, val db: DoubleArray) {
    fun timeOf(i: Int): Duration = step * i

    companion object {
        val STEP = 100.milliseconds
        private const val RATE = 16_000

        /**
         * Niveau de la piste micro de [media], décodée en entier (16 kHz mono) ; null sans piste micro séparée. Rapide :
         * le son seul, 4 s pour une partie de 45 min, 10 s pour 1 h 46 (mesuré sur les captures WARDOGS).
         */
        suspend fun measure(ffmpeg: FfmpegService, media: MediaInfo, layout: AudioLayout): MicLevels? {
            val mic = AudioTracks.of(media.audio, layout).indexOf(AudioRole.MIC) ?: return null
            val window = (RATE * STEP.inWholeMicroseconds / 1_000_000).toInt()
            val levels = ArrayList<Double>((media.duration / STEP).toInt() + 1)
            ffmpeg.run(
                FfmpegCommand(
                    listOf("-i", media.path.toString(), "-map", "0:a:$mic", "-vn", "-ac", "1", "-ar", "$RATE", "-f", "s16le", "-acodec", "pcm_s16le", "pipe:1"),
                    "micro de ${media.path.fileName}",
                ),
                StdoutHandler.Binary { input ->
                    val buffer = ByteArray(1 shl 16)
                    var sum = 0.0
                    var n = 0
                    var odd = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        var k = 0
                        // Un échantillon (s16le, poids faible d'abord) peut chevaucher deux lectures.
                        if (odd >= 0 && read > 0) {
                            val v = ((buffer[0].toInt() shl 8) or odd) / 32768.0
                            sum += v * v; n++; k = 1; odd = -1
                        }
                        while (k + 1 < read) {
                            val v = ((buffer[k + 1].toInt() shl 8) or (buffer[k].toInt() and 0xFF)) / 32768.0
                            sum += v * v
                            k += 2
                            if (++n >= window) {
                                levels += toDb(sum / n)
                                sum = 0.0
                                n = 0
                            }
                        }
                        if (k < read) odd = buffer[k].toInt() and 0xFF
                    }
                    if (n > window / 2) levels += toDb(sum / n)
                },
            )
            return MicLevels(STEP, levels.toDoubleArray())
        }

        internal fun toDb(meanSquare: Double) = 20 * log10(sqrt(meanSquare) + 1e-9)
    }
}

/**
 * Trouve les réactions du joueur : éclats de voix sur le micro, rires et cris de YAMNet ([ScoredTimeline.segments]).
 *
 * Le niveau habituel est la médiane des tranches où le micro n'est pas muet : chaque joueur, chaque micro, chaque
 * réglage de gain a le sien (WARDOGS : −33,5 et −33,8 dBFS sur deux soirées, voix active 3 % et 11 % du temps). Un éclat
 * est une suite de tranches au-dessus de cette médiane + `rise`, tolérant des creux de 200 ms (entre deux syllabes).
 * Le jeu ne fuit presque pas dans ce micro (corrélation 0,13 à 0,20 avec la piste du jeu) : une explosion ne fait pas
 * un éclat.
 */
object ReactionFinder {
    /** Creux toléré dans un éclat, en tranches de 100 ms. */
    private const val HOLES = 2

    fun find(levels: MicLevels, timeline: ScoredTimeline, settings: ReactionMontage, causes: Set<String>): List<Reaction> {
        val db = levels.db
        val active = db.filter { it > settings.silenceDb }.sorted()
        val bursts = mutableListOf<Reaction>()
        if (active.isNotEmpty()) {
            val habitual = active[active.size / 2]
            val threshold = habitual + settings.rise
            var i = 0
            while (i < db.size) {
                if (db[i] < threshold) { i++; continue }
                var j = i
                var last = i
                while (j < db.size && j - last <= HOLES) {
                    if (db[j] >= threshold) last = j
                    j++
                }
                val range = TimeRange(levels.timeOf(i), levels.timeOf(last + 1))
                if (range.length >= settings.minLength) {
                    val top = (i..last).maxBy { db[it] }
                    bursts += Reaction(range, levels.timeOf(top), db[top] - habitual, ReactionKind.VOICE)
                }
                i = last + 1
            }
            log.info { "Micro : voix habituelle ${"%.1f".format(habitual)} dBFS, seuil ${"%.1f".format(threshold)}, ${bursts.size} éclat(s)" }
        }
        // YAMNet : un rire ou un cri reconnu est une réaction, même pas plus fort que la voix habituelle.
        val recognized = timeline.segments.filter { it.kind == "laughter" || it.kind == "shout" }.map { s ->
            val kind = if (s.kind == "laughter") ReactionKind.LAUGH else ReactionKind.SHOUT
            Reaction(s.range, s.range.start, settings.rise * s.confidence.coerceIn(0.0, 1.0) * 2, kind)
        }
        val merged = merge((bursts + recognized).sortedBy { it.range.start }, settings.mergeGap)
        val causeAt = timeline.events.filter { it.kind in causes }
        val withCause = merged.map { r ->
            val cause = causeAt.filter { it.at <= r.range.start + 500.milliseconds && r.range.start - it.at <= settings.causeWindow }.maxByOrNull { it.at }
            r.copy(cause = cause?.kind).let { it.copy(strength = strength(it, settings)) }
        }
        return spaced(withCause, settings.spacing)
    }

    /**
     * Force d'une réaction : son dépassement de la voix habituelle (1 au seuil), un peu sa durée (un fou rire de 3 s
     * compte plus qu'un « ah »), un rire reconnu, et une cause dans le jeu ([ReactionMontage.causeBonus]).
     */
    fun strength(r: Reaction, settings: ReactionMontage): Double {
        val loud = r.rise / settings.rise
        val length = (r.range.length.inWholeMilliseconds / 1000.0).coerceAtMost(3.0) / 3.0
        val laugh = if (r.kind == ReactionKind.LAUGH) 0.5 else 0.0
        val cause = if (r.cause != null) settings.causeBonus else 0.0
        return loud + 0.5 * length + laugh + cause
    }

    /** Éclats rapprochés réunis : un rire en trois souffles, une phrase criée en deux. */
    internal fun merge(sorted: List<Reaction>, gap: Duration): List<Reaction> {
        val out = mutableListOf<Reaction>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.range.start <= last.range.end + gap) {
                val loudest = if (r.rise > last.rise) r else last
                val kind = listOf(last.kind, r.kind).minBy { it.ordinal }
                out[out.lastIndex] = loudest.copy(range = last.range.union(r.range), kind = kind)
            } else {
                out += r
            }
        }
        return out
    }

    /** La plus forte de réactions voisines de moins de [spacing] : un même moment ne fait pas deux plans. */
    internal fun spaced(reactions: List<Reaction>, spacing: Duration): List<Reaction> {
        val kept = mutableListOf<Reaction>()
        for (r in reactions.sortedByDescending { it.strength }) {
            if (kept.none { (it.range.start - r.range.start).absoluteValue < spacing }) kept += r
        }
        return kept.sortedBy { it.range.start }
    }
}

/** Réactions d'une session, et la session où elles deviennent des ancres de plan. */
data class SessionReactions(val session: Session, val reactions: List<Reaction>)

object Reactions {
    /**
     * La session avec ses réactions dans la chronologie : un événement [ReactionMontage.event] au début de chacune
     * (l'ancre du plan, comme un kill) et un segment `laughter` ou `shout` sur l'éclat, que le plan ne coupe pas et sous
     * lequel la musique s'efface. Les événements du jeu restent (ils situent la cause) ; la session d'origine n'est pas
     * modifiée sur le disque.
     */
    fun annotate(session: Session, reactions: List<Reaction>, settings: ReactionMontage): Session {
        val t = session.timeline
        val events = (t.events + reactions.map { TimelineEvent(it.range.start, settings.event, it.strength, DETECTOR) }).sortedBy { it.at }
        val segments = (t.segments + reactions.map { TimelineSegment(it.range, it.kind.segment, 1.0, DETECTOR) }).sortedBy { it.range.start }
        return session.copy(timeline = t.copy(events = events, segments = segments))
    }

    /**
     * Classement des plans par la force de la réaction plutôt que par le score de l'analyse (qui mesure l'action du jeu) :
     * la note du groupe devient la force, ramenée entre 0 et 1 sur l'ensemble.
     */
    fun rescore(groups: List<KillGroup>, found: List<SessionReactions>): List<KillGroup> {
        val all = found.flatMap { s -> s.reactions.map { s.session.media.path to it } }
        val max = all.maxOfOrNull { it.second.strength }?.takeIf { it > 0 } ?: return groups
        return groups.map { g ->
            val r = all.filter { it.first == g.media.path }.minByOrNull { (it.second.range.start - g.kills.first()).absoluteValue }?.second
            if (r == null) g else g.copy(score = r.strength / max)
        }
    }

    /** Réaction ancrée par ce groupe (à 100 ms près), pour le rapport. */
    fun of(group: KillGroup, found: List<SessionReactions>): Reaction? =
        found.filter { it.session.media.path == group.media.path }.flatMap { it.reactions }
            .firstOrNull { (it.range.start - group.kills.first()).absoluteValue <= 100.milliseconds }

    fun describe(r: Reaction): String =
        "${Durations.format(r.range.start)} ${r.kind.name.lowercase()} +${"%.1f".format(r.rise)} dB${r.cause?.let { " après $it" } ?: ""} (force ${"%.2f".format(r.strength)})"

    private const val DETECTOR = "reaction-montage"
}
